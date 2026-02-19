# Architecture Decision Records (ADR)

This document captures the key architectural decisions made in SynthetiQ, their rationale, and the tradeoffs accepted.

---

## ADR-001: Event-Driven Over Synchronous Webhook Processing

**Status**: Accepted

**Context**: GitHub webhooks have a 10-second timeout. AI model inference takes 5-30 seconds. Processing webhooks synchronously risks timeouts and GitHub retrying, creating duplicates.

**Decision**: Immediately ACK the webhook (202 Accepted), persist the request, and queue it on SQS for asynchronous processing.

**Consequences**:
- (+) Webhook response time < 100ms — well within GitHub's timeout
- (+) SQS provides at-least-once delivery with DLQ for failed messages
- (+) Decouples intake rate from processing capacity
- (-) Added complexity of queue management and eventual consistency
- (-) User doesn't get immediate feedback (review is async)

**Alternatives considered**: Synchronous processing with lighter models (rejected — still risky at scale), WebSocket notifications to PR (over-engineered for MVP).

---

## ADR-002: Virtual Threads Over Reactive Programming

**Status**: Accepted

**Context**: Agent analysis involves blocking I/O (AI model calls, GitHub API). Need concurrent execution without thread exhaustion.

**Decision**: Java 21 virtual threads with `Executors.newVirtualThreadPerTaskExecutor()` and `CompletableFuture` for fan-out.

**Consequences**:
- (+) Familiar imperative programming model — no Mono/Flux learning curve
- (+) Debugging is straightforward (real stack traces)
- (+) Spring Boot 3.4 has first-class virtual thread support
- (-) Less ecosystem maturity than Project Reactor
- (-) Risk of "pinning" with synchronized blocks (mitigated by avoiding them)
- (-) No built-in backpressure (delegated to SQS visibility timeout)

---

## ADR-003: Tiered AI Model Routing

**Status**: Accepted

**Context**: AI inference is the primary cost driver. Claude at $3/M tokens would cost $90/month at moderate scale. Most tasks don't need Claude-level intelligence.

**Decision**: Three-tier routing — LOCAL (Ollama, $0), CHEAP (Bedrock Nova, $0.04/M), SMART (Bedrock Claude, $3/M) — with automatic fallback chain.

**Consequences**:
- (+) 80%+ cost reduction compared to using Claude for everything
- (+) Graceful degradation when expensive models are unavailable
- (+) Per-agent tier ceilings prevent accidental cost spikes
- (-) Lower-tier models produce less accurate analysis
- (-) Routing logic adds complexity
- (-) Need to maintain prompts optimized for each model's capabilities

---

## ADR-004: PostgreSQL + JSONB Over DynamoDB for Primary Storage

**Status**: Accepted

**Context**: Agent results have heterogeneous schemas (security findings ≠ architecture findings). Need flexible storage with rich querying for the dashboard.

**Decision**: PostgreSQL with JSONB columns for agent findings. DynamoDB reserved for pure cache/session data if needed later.

**Consequences**:
- (+) JSONB supports indexed queries on findings (GIN index)
- (+) Flyway migrations for auditable, version-controlled schema changes
- (+) Rich SQL aggregations for cost tracking views
- (+) RDS Free Tier: 750 hours/month, 20GB storage
- (-) Requires connection pooling management (HikariCP)
- (-) Cannot auto-scale writes like DynamoDB
- (-) Single point of failure without read replicas (acceptable at MVP scale)

---

## ADR-005: Strategy Pattern Over A2A Protocol for Agent Communication

**Status**: Accepted (may evolve)

**Context**: Agents need to be discovered, dispatched, and their results aggregated. Spring AI's A2A Protocol enables cross-process agent communication.

**Decision**: Use the Strategy pattern (`List<CodeReviewAgent>`) with Spring DI for in-process agent orchestration. Defer A2A to when agents need to run as separate services.

**Consequences**:
- (+) Zero overhead — no network hops, no serialization
- (+) Simple testing — inject mock agents directly
- (+) Adding new agents = implementing an interface + @Component
- (-) All agents must run in the same JVM
- (-) Cannot scale individual agents independently
- (-) Migration to A2A will require wrapping each agent in an endpoint

---

## ADR-006: Idempotent Webhook Processing

**Status**: Accepted

**Context**: GitHub retries webhooks on timeout or 5xx. Without idempotency, we'd process the same PR event multiple times.

**Decision**: Use the `X-GitHub-Delivery` header as an idempotency key, stored as a unique constraint in the database. Duplicate deliveries return the existing review ID.

**Consequences**:
- (+) Exactly-once processing semantics for end users
- (+) Minimal overhead (single DB lookup)
- (+) Works correctly under concurrent webhook deliveries
- (-) Requires unique index maintenance
- (-) Stale idempotency keys accumulate (mitigated by TTL cleanup job)

---

## ADR-007: Simplified Outbox Pattern

**Status**: Accepted (with known risk)

**Context**: After persisting a review, we need to publish a message to SQS. If the app crashes between DB commit and SQS send, the message is lost.

**Decision**: Use `@TransactionalEventListener` to send SQS messages after transaction commit. Accept the small risk of message loss on crash.

**Consequences**:
- (+) No outbox table or polling infrastructure
- (+) Simpler codebase
- (-) Tiny window for message loss (crash between commit and SQS send)
- (-) Mitigated by GitHub webhook retries (creates a new review on retry)
- (-) At higher scale, migrate to a proper outbox with a scheduled poller
