package dev.synthetiq.config;

import dev.synthetiq.domain.enums.AiTier;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI routing config. defaultTier is the fallback; maxTier is the global cost ceiling.
 */
@ConfigurationProperties(prefix = "synthetiq.ai")
public record AiProperties(AiTier defaultTier, AiTier maxTier, int maxInputTokens, int maxOutputTokens) {
    public AiProperties {
        if (defaultTier == null) defaultTier = AiTier.LOCAL;
        if (maxTier == null) maxTier = AiTier.SMART;
        if (maxInputTokens <= 0) maxInputTokens = 4096;
        if (maxOutputTokens <= 0) maxOutputTokens = 2048;
    }
    public AiTier effectiveTier(AiTier requested) {
        if (requested == null) return defaultTier;
        return requested.isWithinBudget(maxTier) ? requested : maxTier;
    }
}
