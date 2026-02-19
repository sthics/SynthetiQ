package dev.synthetiq.infrastructure.ai;

import dev.synthetiq.config.AiProperties;
import dev.synthetiq.domain.enums.AiTier;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;

/**
 * Core cost optimization: routes AI requests to the cheapest capable model.
 *
 * Fallback chain: SMART → CHEAP → LOCAL
 * Each tier has its own circuit breaker.
 *
 * TODO: Inject Spring AI ChatClient beans for each provider.
 */
@Component
public class AiModelRouter {
    private static final Logger log = LoggerFactory.getLogger(AiModelRouter.class);
    private final AiProperties aiProperties;

    public AiModelRouter(AiProperties aiProperties) { this.aiProperties = aiProperties; }

    public AiResponse route(String prompt, AiTier requested) {
        AiTier effective = aiProperties.effectiveTier(requested);
        Instant start = Instant.now();
        try {
            String response = switch (effective) {
                case SMART -> callBedrockClaude(prompt);
                case CHEAP -> callBedrockNova(prompt);
                case LOCAL -> callOllama(prompt);
            };
            return new AiResponse(response, effective, Duration.between(start, Instant.now()), true);
        } catch (Exception e) {
            log.warn("Tier {} failed, trying fallback", effective);
            return attemptFallback(prompt, effective);
        }
    }

    @CircuitBreaker(name = "bedrock-ai") @RateLimiter(name = "bedrock-ai")
    private String callBedrockClaude(String prompt) {
        // TODO: return claudeClient.prompt().user(prompt).call().content();
        throw new UnsupportedOperationException("Bedrock Claude not configured");
    }

    @CircuitBreaker(name = "bedrock-ai")
    private String callBedrockNova(String prompt) {
        throw new UnsupportedOperationException("Bedrock Nova not configured");
    }

    @CircuitBreaker(name = "ollama-ai")
    private String callOllama(String prompt) {
        throw new UnsupportedOperationException("Ollama not configured");
    }

    private AiResponse attemptFallback(String prompt, AiTier failed) {
        try {
            String response = switch (failed) {
                case SMART -> callBedrockNova(prompt);
                case CHEAP -> callOllama(prompt);
                case LOCAL -> throw new RuntimeException("No fallback below LOCAL");
            };
            AiTier fallback = failed == AiTier.SMART ? AiTier.CHEAP : AiTier.LOCAL;
            return new AiResponse(response, fallback, Duration.ZERO, true);
        } catch (Exception e) {
            return new AiResponse("AI analysis unavailable.", failed, Duration.ZERO, false);
        }
    }

    public record AiResponse(String content, AiTier tierUsed, Duration latency, boolean success) {}
}
