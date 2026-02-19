package dev.synthetiq.service;

import dev.synthetiq.domain.entity.ReviewRequest;
import dev.synthetiq.domain.event.ReviewRequestedEvent;
import dev.synthetiq.repository.ReviewRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * Command-side service. Creates reviews with idempotency check,
 * publishes domain events for SQS queueing.
 */
@Service
public class ReviewService {
    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private final ReviewRequestRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public ReviewService(ReviewRequestRepository repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public UUID createReview(String idempotencyKey, String repoFullName, int prNumber,
                             String headSha, String baseBranch, long installationId) {
        return repository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    log.info("Duplicate delivery: {} → {}", idempotencyKey, existing.getId());
                    return existing.getId();
                })
                .orElseGet(() -> {
                    ReviewRequest review = ReviewRequest.create(
                            idempotencyKey, repoFullName, prNumber, headSha, baseBranch, installationId);
                    repository.save(review);
                    eventPublisher.publishEvent(new ReviewRequestedEvent(
                            review.getId(), repoFullName, prNumber, headSha, installationId, Instant.now()));
                    log.info("Created review {} for {}/pull/{}", review.getId(), repoFullName, prNumber);
                    return review.getId();
                });
    }
}
