package dev.synthetiq.dto.response;

import dev.synthetiq.domain.enums.ReviewStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReviewResponse(
        UUID id, String repository, int pullRequestNumber, String headSha,
        ReviewStatus status, int agentResultCount,
        List<AgentResultSummary> results, Instant createdAt, Instant completedAt
) {
    public record AgentResultSummary(String agentType, boolean success, String summary,
                                      String aiTierUsed, long durationMs) {}
}
