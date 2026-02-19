package dev.synthetiq.infrastructure.aws;

import dev.synthetiq.domain.event.ReviewRequestedEvent;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges domain events to SQS messages.
 *
 * <p>
 * Uses {@code @TransactionalEventListener} to ensure the message is only
 * sent AFTER the transaction commits. This prevents a race condition where
 * the SQS consumer tries to load a review that hasn't been committed yet.
 *
 * <p>
 * Tradeoff: This is a "simplified outbox pattern." A strict outbox would
 * write to an outbox table in the same transaction and poll/CDC it to SQS.
 * Our approach has a small window where the transaction commits but SQS
 * send fails (crash between commit and send). At our scale, this is
 * acceptable — the webhook will be retried by GitHub, creating a new review.
 * At higher scale, implement the full outbox with a scheduled poller.
 */
@Component
@Profile("!local")
public class ReviewTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReviewTaskPublisher.class);

    private final SqsTemplate sqsTemplate;
    private final String queueName;

    public ReviewTaskPublisher(SqsTemplate sqsTemplate,
            @Value("${synthetiq.sqs.review-queue}") String queueName) {
        this.sqsTemplate = sqsTemplate;
        this.queueName = queueName;
    }

    @TransactionalEventListener
    public void onReviewRequested(ReviewRequestedEvent event) {
        log.info("Publishing review task to SQS: reviewId={}, repo={}/pull/{}",
                event.reviewId(), event.repositoryFullName(), event.pullRequestNumber());

        sqsTemplate.send(queueName, event.reviewId().toString());

        log.debug("Review task published to queue: {}", queueName);
    }
}
