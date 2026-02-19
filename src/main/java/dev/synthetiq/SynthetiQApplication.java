package dev.synthetiq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * SynthetiQ — Event-driven multi-agent code review platform.
 *
 * <p>Architecture overview:
 * <pre>
 * GitHub Webhook → WebhookController → SQS (decouple) → EventListener
 *   → ReviewSaga (state machine) → AgentOrchestrator → [Agent1, Agent2, ...]
 *   → ResultAggregator → GitHubClient (post review comments)
 * </pre>
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>Async-first: webhooks return 202 immediately, all processing is event-driven</li>
 *   <li>Dual-model AI: Ollama (free) handles volume, Bedrock handles complexity</li>
 *   <li>Saga pattern: multi-agent reviews are long-running, need compensation on failure</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class SynthetiQApplication {

    public static void main(String[] args) {
        SpringApplication.run(SynthetiQApplication.class, args);
    }
}
