# SynthetiQ

> **⚠️ Project Sunset — July 2026**
>
> **SynthetiQ is no longer actively maintained or hosted.** My AWS free tier expired and the infrastructure costs (RDS, SQS, Bedrock) became unsustainable for a solo project. The hosted GitHub App has been deactivated.
>
> **To existing users:** I'm sorry for the disruption. If you were relying on SynthetiQ for your PRs, you'll need to uninstall the GitHub App from your repos. Thank you for trying it out — it meant a lot.
>
> **The code is fully open source** — feel free to clone, fork, and self-host. Everything you need is below. If you do spin it up, I'd love to hear about it.

---

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

## Self-Hosting

Since the hosted version is no longer available, here's how to run it yourself:

### Prerequisites
- Java 21+
- Docker (for PostgreSQL & integration tests)
- An AWS account (for SQS + Bedrock) — or use local mode with Ollama
- A GitHub App (for webhook integration)

### Local Development (No AWS Needed)

```bash
# Runs with H2 in-memory DB + Ollama for AI — completely free
mvn spring-boot:run

# Tests
mvn test          # unit tests
mvn verify        # unit + integration (needs Docker)
```

Runs on port **8090**. Swagger at `/swagger-ui.html`.

### Production Deployment

You'll need to configure:
- PostgreSQL 16 instance
- AWS SQS queue (or swap for another message broker)
- AWS Bedrock access (or use Ollama for a free alternative)
- GitHub App credentials (webhook secret, private key, app ID)

See `application.yml` and `application-local.yml` for all configuration options.

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
