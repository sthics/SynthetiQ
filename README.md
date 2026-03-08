# SynthetiQ

Multi-agent code review platform. A GitHub App that runs specialized AI agents in parallel on pull requests and posts inline review comments with one-click fix suggestions.

## How It Works

```
GitHub PR Webhook → SQS → ReviewOrchestrator → [Security, Architecture, Performance, Refactoring] → GitHub PR Review
```

1. PR opened/updated → webhook received, queued via SQS
2. Orchestrator fans out to eligible agents in parallel (virtual threads)
3. Each agent analyzes the diff and returns findings with severity + suggested fixes
4. Results aggregated into a severity-grouped summary comment + inline comments on the diff
5. CRITICAL/HIGH findings get inline comments with GitHub suggestion blocks (one-click apply)

## Tech Stack

| | |
|---|---|
| **Runtime** | Java 21, Spring Boot 4.0, Spring AI 2.0 |
| **AI** | Tiered routing: Ollama (local/free) → Bedrock Nova (cheap) → Claude (smart) |
| **Queue** | AWS SQS (Spring Cloud AWS) |
| **Database** | PostgreSQL 16, Flyway migrations, JSONB for agent output |
| **Resilience** | Resilience4j circuit breakers + rate limiters |
| **Testing** | Testcontainers, WireMock, JUnit 5 |

## Quick Start

```bash
# Local dev (H2 + Ollama, no AWS needed)
mvn spring-boot:run

# Tests
mvn test          # unit tests
mvn verify        # unit + integration (needs Docker)
```

Runs on port **8090**. Swagger at `/swagger-ui.html`.

## Project Structure

```
src/main/java/dev/synthetiq/
├── agent/               # AI agents (Strategy pattern) + orchestrator
├── config/              # Type-safe @ConfigurationProperties
├── controller/          # Webhook + review REST endpoints
├── domain/              # Entities, enums, events, value objects
├── infrastructure/      # GitHub API, SQS, AI model router
├── service/             # Business logic (CQRS-lite)
└── repository/          # Spring Data JPA
```

## License

MIT
