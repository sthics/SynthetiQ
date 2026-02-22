package dev.synthetiq.infrastructure.ai;

import dev.synthetiq.config.AiProperties;
import dev.synthetiq.domain.enums.AiTier;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Core cost optimization: routes AI requests to the cheapest capable model.
 *
 * <p>All tiers use Amazon Nova models via Bedrock Converse API:
 * <ul>
 *   <li>SMART  → Nova Pro   ($0.80/$3.20 per 1M tokens) — complex reasoning</li>
 *   <li>CHEAP  → Nova Lite  ($0.06/$0.24 per 1M tokens) — pattern matching</li>
 *   <li>LOCAL  → Nova Micro ($0.035/$0.14 per 1M tokens) — simple tasks</li>
 * </ul>
 *
 * <p>Fallback chain: SMART → CHEAP → LOCAL.
 * Each tier has its own circuit breaker.
 */
@Component
public class AiModelRouter {

    private static final Logger log = LoggerFactory.getLogger(AiModelRouter.class);

    private static final String NOVA_PRO = "amazon.nova-pro-v1:0";
    private static final String NOVA_LITE = "amazon.nova-lite-v1:0";
    private static final String NOVA_MICRO = "amazon.nova-micro-v1:0";

    private final AiProperties aiProperties;
    private final ChatModel chatModel;

    public AiModelRouter(AiProperties aiProperties, ChatModel chatModel) {
        this.aiProperties = aiProperties;
        this.chatModel = chatModel;
    }

    public AiResponse route(String prompt, AiTier requested) {
        AiTier effective = aiProperties.effectiveTier(requested);
        Instant start = Instant.now();
        try {
            String response = switch (effective) {
                case SMART -> callNovaPro(prompt);
                case CHEAP -> callNovaLite(prompt);
                case LOCAL -> callNovaMicro(prompt);
            };
            return new AiResponse(response, effective, Duration.between(start, Instant.now()), true);
        } catch (Exception e) {
            log.warn("Tier {} failed, trying fallback: {}", effective, e.getMessage());
            return attemptFallback(prompt, effective);
        }
    }

    @CircuitBreaker(name = "bedrock") @RateLimiter(name = "bedrock-daily")
    private String callNovaPro(String prompt) {
        log.debug("Routing to Nova Pro (SMART tier)");
        return callWithModel(prompt, NOVA_PRO);
    }

    @CircuitBreaker(name = "bedrock")
    private String callNovaLite(String prompt) {
        log.debug("Routing to Nova Lite (CHEAP tier)");
        return callWithModel(prompt, NOVA_LITE);
    }

    @CircuitBreaker(name = "bedrock")
    private String callNovaMicro(String prompt) {
        log.debug("Routing to Nova Micro (LOCAL tier)");
        return callWithModel(prompt, NOVA_MICRO);
    }

    private String callWithModel(String prompt, String modelId) {
        ChatOptions options = ChatOptions.builder()
                .model(modelId)
                .temperature(0.1)
                .maxTokens(aiProperties.maxOutputTokens())
                .build();
        ChatResponse response = chatModel.call(new Prompt(prompt, options));
        return response.getResult().getOutput().getText();
    }

    private AiResponse attemptFallback(String prompt, AiTier failed) {
        try {
            String response = switch (failed) {
                case SMART -> callNovaLite(prompt);
                case CHEAP -> callNovaMicro(prompt);
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
