package dev.synthetiq.domain.enums;

/**
 * AI model tiers ordered by cost and capability.
 *
 * LOCAL  = Ollama ($0) | CHEAP = Bedrock Nova (~$0.04/M) | SMART = Bedrock Claude (~$3/M)
 */
public enum AiTier {
    LOCAL(0), CHEAP(1), SMART(2);

    private final int rank;
    AiTier(int rank) { this.rank = rank; }

    public boolean isWithinBudget(AiTier ceiling) {
        return this.rank <= ceiling.rank;
    }
}
