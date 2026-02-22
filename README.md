# SynthetiQ

**Event-Driven Multi-Agent Code Review & Refactoring Platform**

A GitHub App that orchestrates specialized AI agents to perform deep code analysis, detect Spring Boot migration issues, and post actionable review comments on pull requests. Built with Spring Boot 4.0, Spring AI 2.0, and AWS — running for under $5/monthz. 

## Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   GitHub App    │────▶│  Spring Boot     │────▶│  SQS Queue      │
│   (Webhooks)    │     │  Webhook Handler │     │  (Review Tasks)  │
└─────────────────┘     └──────────────────┘     └────────┬────────┘
                               │                           │
                        ┌──────┴──────┐           ┌────────▼────────┐
                        │  PostgreSQL │           │  Orchestrator   │
                        │  (State)    │           │  (Fan-out/in)   │
                        └─────────────┘           └────────┬────────┘
                                                           │
                            ┌──────────────────────────────┼──────────────┐
                            ▼                              ▼              ▼
                     ┌──────────────┐          ┌───────────────┐  ┌──────────────┐
                     │  Security    │          │ Architecture  │  │ Performance  │
                     │  Agent       │          │ Agent         │  │ Agent        │
                     │  (CHEAP)     │          │ (SMART)       │  │ (LOCAL)      │
                     └──────┬───────┘          └───────┬───────┘  └──────┬───────┘
                            │                          │                  │
                            └──────────────────────────┼──────────────────┘
                                                       ▼
                                              ┌────────────────┐
                                              │ GitHub PR      │
                                              │ Review Comment │
                                              └────────────────┘
```

## Key Design Decisions

### Why SQS over Kafka/RabbitMQ?
SQS is fully managed with a 1M request/month free tier. We don't need message replay (events are persisted in PostgreSQL) or pub-sub fanout (SNS handles that). The tradeoff is higher latency (~10-50ms) which is acceptable for async code review.

### Why Virtual Threads over Reactive (WebFlux)?
Agent analysis involves blocking I/O (AI model calls, GitHub API). Virtual threads handle this natively without platform thread exhaustion, while being dramatically simpler than reactive chains. We use WebClient for non-blocking outbound HTTP, but keep the server-side synchronous.

### Why Tiered AI Routing?
The model router is the core cost optimization. 80% of tasks (linting, formatting) go to free Ollama, 15% (security scanning) to cheap Bedrock Nova, and 5% (complex refactoring) to Claude. This keeps costs under $5/month at resume-worthy scale.

### Why PostgreSQL + JSONB over DynamoDB?
Rich querying (JSONB for heterogeneous agent output), Flyway migrations for auditable schema evolution, and JPA integration maturity. DynamoDB is better for pure key-value with TTL, but our access patterns include joins and aggregations.

## Tech Stack

| Layer | Technology | Why |
|-------|-----------|-----|
| Framework | Spring Boot 4.0.2 + Java 21 | Virtual threads, records, Spring Framework 7 |
| AI | Spring AI 2.0 + Bedrock + Ollama | Tiered routing, cost optimization |
| Queue | AWS SQS (Spring Cloud AWS 4.0) | Free tier, dead-letter queues, managed |
| Database | PostgreSQL 16 (RDS Free Tier) | JSONB, Flyway, 750 hrs/month free |
| Resilience | Resilience4j | Circuit breakers, rate limiters |
| Observability | Micrometer + Prometheus | Metrics, cost tracking |
| CI/CD | GitHub Actions | Free for public repos |
| Testing | Testcontainers + WireMock | Real infrastructure in tests |

## Getting Started

### Prerequisites
- Java 21+
- Maven 3.9+
- Docker & Docker Compose *(only for `dev` profile)*

### Local Development (Zero Dependencies)

The `local` profile uses H2 in-memory DB and Ollama — no Docker needed.

```bash
# Run the application (starts on port 8090)
mvn spring-boot:run

# Run unit tests
mvn test

# Run all tests (unit + integration)
mvn verify
```

The app will be available at `http://localhost:8090`. Swagger UI at `/swagger-ui.html`, H2 console at `/h2-console`.

### Full Stack Development (Docker)

```bash
# 1. Start Postgres, LocalStack, Ollama
docker compose up -d

# 2. Run with dev profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Environment Variables

See `.env` for a complete template. Key variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `GITHUB_APP_ID` | GitHub App ID (numeric) | `0` |
| `GITHUB_PRIVATE_KEY_PATH` | Path to PEM private key | - |
| `GITHUB_WEBHOOK_SECRET` | Webhook HMAC secret | `dev-secret-not-for-production` |
| `AWS_REGION` | AWS region | `us-east-1` |
| `AWS_ACCESS_KEY_ID` | AWS access key | `test` |
| `AWS_SECRET_ACCESS_KEY` | AWS secret key | `test` |

## Project Structure

```
src/main/java/dev/synthetiq/
├── config/              # Type-safe configuration properties
├── controller/          # REST endpoints (webhook, review API)
├── domain/
│   ├── entity/          # JPA entities (ReviewRequest, AgentResult)
│   ├── enums/           # Status, tier, severity enums
│   ├── event/           # Domain events
│   └── valueobject/     # Immutable value objects (CodeFile)
├── dto/                 # Request/response DTOs
├── exception/           # Global error handling (RFC 7807)
├── infrastructure/
│   ├── ai/              # AI model router (tiered cost optimization)
│   ├── aws/             # SQS listener/publisher
│   └── github/          # GitHub API client, token provider, HMAC
├── agent/
│   ├── CodeReviewAgent  # Agent interface (Strategy pattern)
│   ├── security/        # Vulnerability detection agent
│   ├── architecture/    # Pattern + migration detection agent
│   ├── performance/     # Performance anti-pattern agent
│   └── orchestrator/    # Fan-out/fan-in coordinator
├── service/             # Business logic (CQRS-lite)
└── repository/          # Spring Data JPA repositories
```

## Cost Optimization

| Scenario | Monthly Cost |
|----------|-------------|
| Development (solo) | $0 |
| Portfolio demo (few users) | ~$0.36 |
| Resume-worthy (10 repos, 100 PRs) | ~$3.87 |
| Production (100+ repos) | ~$20-50 |

Key techniques: tiered model routing, prompt caching, token limits, SQS-based backpressure, Resilience4j rate limiters.

## Roadmap

- [ ] Phase 1: Core pipeline (webhook → agents → PR comment)
- [ ] Phase 2: Spring Boot 2→3 migration specialization
- [ ] Phase 3: Refactoring agent (auto-creates fix PRs)
- [ ] Phase 4: A2A Protocol for cross-process agent communication
- [ ] Phase 5: Multi-tenant SaaS with Stripe billing

## License

MIT
