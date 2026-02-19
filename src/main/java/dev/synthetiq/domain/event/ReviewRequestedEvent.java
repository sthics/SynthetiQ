package dev.synthetiq.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published when a PR webhook is received and persisted.
 * Consumed by the SQS publisher to queue the review for async processing.
 */
public record ReviewRequestedEvent(
        UUID reviewId,
        String repositoryFullName,
        int pullRequestNumber,
        String headSha,
        long installationId,
        Instant occurredAt
) {
    public ReviewRequestedEvent {
        if (reviewId == null) throw new IllegalArgumentException("reviewId required");
        if (occurredAt == null) occurredAt = Instant.now();
    }
}
