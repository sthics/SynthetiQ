# SynthetiQ Project Guide

> This file is read by SynthetiQ agents during code review to enforce project-specific conventions.

## Java & Spring Boot

- **Java 21 required** — use records, sealed classes, pattern matching, virtual threads, text blocks
- **Spring Boot 4 / Spring Framework 7 / Jakarta EE** — never use `javax.*` imports, always `jakarta.*`
- **Constructor injection only** — never use `@Autowired` on fields. All dependencies via constructor params.
- **DTOs must be records** — no mutable POJOs for request/response types, config properties, or value objects

## Jackson

- **Jackson 3 only** — imports must be `tools.jackson.databind.*`, NOT `com.fasterxml.jackson.*`
- The `com.fasterxml` variant exists as a transitive dependency — do not use it directly

## Architecture

- **CQRS-lite** — separate command services (`ReviewService`) from query services (`ReviewQueryService`)
- **No business logic in controllers** — controllers handle HTTP mapping only, delegate to services
- **Strategy pattern for agents** — implement `CodeReviewAgent` interface, auto-discovered via `List<CodeReviewAgent>` DI
- **Split transactions in orchestrator** — never put `@Transactional` on the entire orchestration method
- **Never pass JPA entities across thread boundaries** — use immutable records (`ReviewSnapshot`, etc.)

## Concurrency

- **Use the `agentExecutorService` bean** for all async agent work — never create raw `Executors.newVirtualThreadPerTaskExecutor()`
- MDC context (correlationId, reviewId) must propagate to agent threads

## Security

- **HMAC-SHA256 webhook verification** is mandatory — never skip signature checks
- **No hardcoded secrets** — passwords, tokens, API keys must come from environment variables or config
- **No SQL string concatenation** — always use parameterized queries or Spring Data methods

## Error Handling

- **RFC 7807 Problem Details** via `GlobalExceptionHandler` — never return raw exception messages to clients
- **Resilience4j** for circuit breakers and rate limiters on external calls (GitHub API, AI models)

## Testing

- Unit tests with JUnit 5 + AssertJ + Mockito
- `@WebMvcTest` for controller slices
- Integration tests use `*IT` suffix, run via `mvn verify` with Testcontainers
- WireMock for external HTTP API mocking
- **Every new agent must have ranking tests** verifying file prioritization logic

## Naming

- Packages: `dev.synthetiq.<layer>.<feature>`
- No `/api/` prefix on routes — there is no context path
- Config properties: type-safe records via `@ConfigurationProperties`
