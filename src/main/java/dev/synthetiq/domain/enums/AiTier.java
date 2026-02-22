package dev.synthetiq.domain.enums;

/**
 * AI model tiers ordered by cost and capability.
 *
 * LOCAL = Nova Micro ($0.035/$0.14 per 1M tokens)
 * CHEAP = Nova Lite  ($0.06/$0.24 per 1M tokens)
 * SMART = Nova Pro   ($0.80/$3.20 per 1M tokens)
 */
public enum AiTier {
    LOCAL(0), CHEAP(1), SMART(2);

    private final int rank;
    AiTier(int rank) { this.rank = rank; }

    public boolean isWithinBudget(AiTier ceiling) {
        return this.rank <= ceiling.rank;
    }
}
