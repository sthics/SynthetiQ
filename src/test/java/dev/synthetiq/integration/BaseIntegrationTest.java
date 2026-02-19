package dev.synthetiq.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

/**
 * Base class for integration tests.
 *
 * <p>Uses Testcontainers to spin up real PostgreSQL and LocalStack instances.
 * This ensures tests run against the same infrastructure as production,
 * catching issues that H2 or mocks would miss (JSONB queries, SQS behavior).
 *
 * <p>Design decisions:
 * <ul>
 *   <li><b>Shared containers</b> ({@code @Container} + static): One PostgreSQL
 *       and one LocalStack instance per test suite run, not per test class.
 *       Reduces startup time from ~30s to ~3s for subsequent test classes.</li>
 *   <li><b>@DynamicPropertySource</b>: Injects container-specific connection
 *       details at runtime. No hardcoded ports or hosts in test config.</li>
 *   <li><b>Spring profile "test"</b>: Disables real AWS and AI integrations.
 *       Only infrastructure we spin up ourselves is accessible.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class BaseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("synthetiq_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(SQS, S3);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Let Flyway create the schema from scratch for tests
        registry.add("spring.flyway.clean-disabled", () -> "false");

        // LocalStack SQS
        registry.add("spring.cloud.aws.endpoint",
                () -> localstack.getEndpointOverride(SQS).toString());
        registry.add("spring.cloud.aws.region.static", () -> localstack.getRegion());
        registry.add("spring.cloud.aws.credentials.access-key",
                () -> localstack.getAccessKey());
        registry.add("spring.cloud.aws.credentials.secret-key",
                () -> localstack.getSecretKey());

        // Disable real AI and GitHub in tests
        registry.add("spring.ai.bedrock.enabled", () -> "false");
        registry.add("spring.ai.ollama.enabled", () -> "false");
        registry.add("synthetiq.github.webhook-secret", () -> "test-secret");
        registry.add("synthetiq.github.app-id", () -> "12345");
    }
}
