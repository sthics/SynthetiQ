# SynthetiQ — Claude Code Guide

## What This Is

Event-driven multi-agent code review platform. GitHub App that receives PR webhooks, fans out analysis to AI agents in parallel (virtual threads), aggregates results, and posts review comments on the PR.

**Pipeline:** GitHub Webhook → WebhookController (202 Accepted) → SQS → ReviewOrchestrator → [SecurityAgent, ArchitectureAgent, PerformanceAgent, ...] → GitHub PR Comment

## Quick Reference

| | |
|---|---|
| **Java** | 21 (records, virtual threads, pattern matching) |
| **Spring Boot** | 4.0.2 (Spring Framework 7, Jakarta EE) |
| **Jackson** | 3.x (`tools.jackson.*`, NOT `com.fasterxml.jackson.*`) |
| **AI** | Spring AI 2.0.0-M1 (Ollama local, Bedrock Claude/Nova) |
| **Queue** | AWS SQS via Spring Cloud AWS 4.0-RC1 |
| **DB** | PostgreSQL 16 (prod), H2 in-memory (local) |
| **Migrations** | Flyway (`src/main/resources/db/migration/`) |
| **Build** | Maven, `pom.xml` |

## Commands

```bash
mvn spring-boot:run           # Run locally (H2 + Ollama, no AWS needed)
mvn compile                   # Compile only
mvn test                      # Unit tests
mvn verify                    # Unit + integration tests (needs Docker for Testcontainers)
```

- Local dev runs on port **8090** with profile `local`
- Integration tests use Testcontainers (PostgreSQL 16 + LocalStack)

## Project Structure

```
src/main/java/dev/synthetiq/
├── agent/                    # AI agents (Strategy pattern: CodeReviewAgent interface)
│   ├── orchestrator/         # ReviewOrchestrator — fan-out/fan-in coordinator
│   ├── security/             # Vulnerability detection
│   └── architecture/         # Migration/pattern detection
├── config/                   # Spring config + type-safe @ConfigurationProperties (records)
├── controller/               # REST: WebhookController, ReviewController
├── domain/
│   ├── entity/               # JPA: ReviewRequest (aggregate root), AgentResult
│   ├── enums/                # ReviewStatus, AgentType, AiTier, Severity
│   ├── event/                # ReviewRequestedEvent (domain event → SQS)
│   └── valueobject/          # CodeFile, ProjectGuide (immutable)
├── dto/                      # Request/response records (WebhookPayload, ReviewResponse)
├── exception/                # GlobalExceptionHandler (RFC 7807 Problem Details)
├── infrastructure/           # External adapters
│   ├── ai/                   # AiModelRouter (tiered: LOCAL → CHEAP → SMART)
│   ├── aws/                  # SQS publisher/listener
│   └── github/               # GitHubApiClient, WebhookSignatureVerifier, TokenProvider
├── repository/               # Spring Data JPA
└── service/                  # ReviewService (commands), ReviewQueryService (queries)
```

## Architecture Rules

### Transactional Boundaries
ReviewOrchestrator uses **split transactions** — NOT a single `@Transactional` on the orchestration method:
1. `beginReview()` — `@Transactional`: load entity, mark IN_PROGRESS, return `ReviewSnapshot` (immutable record)
2. `executeReview()` — NOT transactional: fetch files, fan out agents via `CompletableFuture`, await results
3. `completeReview()` — `@Transactional`: load entity fresh, persist results, post GitHub comment, mark COMPLETED

**Why:** Async agent threads run outside the Hibernate session. Passing a managed entity to `CompletableFuture` tasks causes `LazyInitializationException` and thread-safety issues. Pass the `ReviewSnapshot` record instead.

### Async & Concurrency
- Agent fan-out uses the `agentExecutorService` bean (virtual threads + MDC propagation)
- Do NOT create `Executors.newVirtualThreadPerTaskExecutor()` directly — use the bean so MDC (correlationId, reviewId) propagates to agent threads
- `AsyncConfig` defines both a `TaskExecutor` (for Spring `@Async`) and an `ExecutorService` (for `CompletableFuture.supplyAsync`)

### Webhook Security
- HMAC-SHA256 verified against **raw request body bytes** before deserialization
- Controller accepts `@RequestBody String rawBody`, verifies signature, then manually deserializes via `ObjectMapper`
- Never skip signature verification — reject with 401 if invalid

### State Machine
`ReviewStatus`: RECEIVED → QUEUED → IN_PROGRESS → COMPLETED/FAILED
- `markInProgress()` accepts both RECEIVED and QUEUED as predecessors (no separate queuing step at MVP)
- Optimistic locking (`@Version`) on ReviewRequest for concurrent agent updates

### Security Config
- Path matchers in `SecurityConfig` must match **actual controller routes** (no `/api/` prefix — there is no context path)
- Webhook endpoint is `POST /webhooks/github`
- Actuator at `/actuator/...`, Swagger at `/swagger-ui/**` and `/v3/api-docs/**`

## Conventions

- **DTOs are records** — `WebhookPayload`, `AgentAnalysisResult`, `CodeFile`, all config properties
- **Jackson 3** — imports are `tools.jackson.databind.ObjectMapper`, not `com.fasterxml.jackson`
- **Config properties** — type-safe records via `@ConfigurationProperties` + `@ConfigurationPropertiesScan`
- **CQRS-lite** — `ReviewService` (commands) vs `ReviewQueryService` (read-only transactions)
- **Agents** — implement `CodeReviewAgent` interface, discovered via `List<CodeReviewAgent>` DI injection. `analyze()` receives `Optional<ProjectGuide>` for repo-aware prompts.
- **Project Guide** — agents read `SYNTHETIQ.md` from the repo root via `GitHubApiClient.getProjectGuide()`. Content is injected into prompts via `PromptUtils.withGuide()`. Soft-capped at 8KB.
- **Error handling** — `GlobalExceptionHandler` returns RFC 7807 Problem Details
- **Profiles** — `local` (H2, Ollama, no AWS), `aws` (PostgreSQL, Bedrock, real SQS)
- **No context path** — controllers map directly: `/webhooks/github`, `/reviews/{id}`

## Testing

- `@WebMvcTest` for controller slices (mocked dependencies, MockMvc)
- `BaseIntegrationTest` for full stack (Testcontainers: PostgreSQL + LocalStack)
- Integration tests use `*IT` suffix and run during `mvn verify` (failsafe plugin)
- WireMock for GitHub API mocking, Awaitility for async assertions

## Key Config Locations

| File | Purpose |
|---|---|
| `application.yml` | Base config (Ollama, resilience4j, Jackson, logging) |
| `application-local.yml` | Local dev (H2, no AWS, port 8090) |
| `application-aws.yml` | Production (RDS, Bedrock, real SQS) |
| `db/migration/V1__initial_schema.sql` | Flyway schema (PostgreSQL DDL) |

## Common Pitfalls

1. **Jackson 3 vs 2**: Spring Boot 4 auto-configures `tools.jackson.databind.ObjectMapper`. The `com.fasterxml` variant is on the classpath only as a transitive dep — don't use it.
2. **No `/api/` prefix**: There is no `server.servlet.context-path`. SecurityConfig matchers and test URLs must use bare paths like `/webhooks/github`.
3. **Don't pass entities to async threads**: Use `ReviewSnapshot` (or similar immutable records) when crossing transaction/thread boundaries.
4. **Local profile excludes AWS auto-config**: If adding new AWS services, add their auto-config to the exclusion list in `application-local.yml`.
5. **Project Guide is supplementary**: `getProjectGuide()` must never fail the review pipeline. It catches all exceptions and returns `Optional.empty()` on error.
