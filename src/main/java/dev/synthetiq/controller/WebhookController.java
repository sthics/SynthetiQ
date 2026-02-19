package dev.synthetiq.controller;

import tools.jackson.databind.ObjectMapper;
import dev.synthetiq.dto.request.WebhookPayload;
import dev.synthetiq.infrastructure.github.WebhookSignatureVerifier;
import dev.synthetiq.service.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * GitHub webhook receiver. Validates HMAC signature, persists, queues,
 * and returns 202 Accepted within ~50ms. All heavy processing is async via SQS.
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {
    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private final WebhookSignatureVerifier signatureVerifier;
    private final ReviewService reviewService;
    private final ObjectMapper objectMapper;

    public WebhookController(WebhookSignatureVerifier signatureVerifier,
                             ReviewService reviewService,
                             ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.reviewService = reviewService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/github")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader("X-GitHub-Delivery") String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String rawBody) {

        // Verify HMAC signature against raw bytes before any deserialization
        if (!signatureVerifier.isValid(rawBody.getBytes(StandardCharsets.UTF_8), signature)) {
            log.warn("Webhook signature verification failed for delivery={}", deliveryId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "rejected", "reason", "invalid signature"));
        }

        if (!"pull_request".equals(eventType))
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "not a PR event"));

        WebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, WebhookPayload.class);
        } catch (Exception e) {
            log.error("Failed to deserialize webhook payload for delivery={}: {}", deliveryId, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "reason", "invalid payload"));
        }

        log.info("Webhook: event={}, delivery={}, action={}", eventType, deliveryId, payload.action());

        if (!payload.isActionable())
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "action not tracked"));

        UUID reviewId = reviewService.createReview(deliveryId,
                payload.repository().fullName(), payload.pullRequest().number(),
                payload.pullRequest().head().sha(), payload.pullRequest().base().ref(),
                payload.installation().id());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "queued", "reviewId", reviewId.toString()));
    }
}
