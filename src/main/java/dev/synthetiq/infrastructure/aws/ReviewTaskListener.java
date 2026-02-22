package dev.synthetiq.infrastructure.aws;

import dev.synthetiq.agent.orchestrator.ReviewOrchestrator;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SQS message consumer for review tasks.
 *
 * <p>
 * Architecture flow:
 * 
 * <pre>
 * Webhook → ReviewService → ApplicationEvent → SQS Publisher → [SQS Queue] → THIS → Orchestrator
 * </pre>
 *
 * <p>
 * Design decisions:
 * <ul>
 * <li><b>SQS over in-process queue</b>: If the app crashes mid-review, the
 * message
 * returns to the queue after the visibility timeout. In-process queues lose
 * messages on crash.</li>
 * <li><b>Single queue with DLQ</b>: Failed messages (after maxReceiveCount
 * retries)
 * move to the dead-letter queue for manual inspection. We don't auto-retry
 * indefinitely because some failures are permanent (repo deleted, etc.).</li>
 * <li><b>Message body = review ID only</b>: The full review data is in
 * PostgreSQL.
 * Sending just the ID keeps messages small and avoids consistency issues if
 * the review is updated between queueing and processing.</li>
 * <li><b>Concurrency = 5</b>: Limits parallel orchestrations to prevent
 * overwhelming the AI providers. Tune based on rate limiter capacity.</li>
 * </ul>
 *
 * <p>
 * Error handling: If the orchestrator throws, Spring Cloud AWS will NOT
 * acknowledge the message. SQS will redeliver it after the visibility timeout.
 * After maxReceiveCount failures (configured on the queue), SQS moves it to the
 * DLQ.
 */
@Component
@Profile("!local")
public class ReviewTaskListener {

    private static final Logger log = LoggerFactory.getLogger(ReviewTaskListener.class);

    private final ReviewOrchestrator orchestrator;

    public ReviewTaskListener(ReviewOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @SqsListener(value = "${synthetiq.sqs.review-queue}", maxConcurrentMessages = "5", maxMessagesPerPoll = "5")
    public void onReviewTask(String message) {
        UUID reviewId;
        try {
            reviewId = UUID.fromString(message.trim().replace("\"", ""));
        } catch (IllegalArgumentException e) {
            log.error("Invalid review ID in SQS message: {}", message);
            return; // Acknowledge (delete) malformed messages — they'll never succeed
        }

        log.info("Processing review task from SQS: {}", reviewId);

        // Delegate to orchestrator — exceptions propagate to SQS for retry
        orchestrator.executeReview(reviewId);
    }
}
