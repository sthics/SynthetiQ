package dev.synthetiq.agent;

import dev.synthetiq.domain.enums.AiTier;
import java.time.Duration;

public record AgentAnalysisResult(
        String findingsJson, String summary, AiTier tierUsed,
        int inputTokens, int outputTokens, Duration duration
) {
    public static AgentAnalysisResult empty() {
        return new AgentAnalysisResult("{\"findings\": []}", "No issues found.",
                AiTier.LOCAL, 0, 0, Duration.ZERO);
    }
}
