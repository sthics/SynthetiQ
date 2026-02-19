package dev.synthetiq.controller;

import dev.synthetiq.infrastructure.github.WebhookSignatureVerifier;
import dev.synthetiq.service.ReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for the webhook controller.
 *
 * <p>
 * Uses @WebMvcTest for a thin slice — only the web layer is loaded.
 * All dependencies are mocked. This tests:
 * - HTTP routing and status codes
 * - HMAC signature verification
 * - Request deserialization
 * - Event type filtering
 */
@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private WebhookSignatureVerifier signatureVerifier;

  @MockitoBean
  private ReviewService reviewService;

  private static final String WEBHOOK_URL = "/webhooks/github";

  @Nested
  @DisplayName("POST /webhooks/github")
  class HandleWebhook {

    @Test
    @DisplayName("should accept valid PR opened event and return 202")
    void shouldAcceptValidPrEvent() throws Exception {
      UUID reviewId = UUID.randomUUID();
      when(signatureVerifier.isValid(any(byte[].class), eq("sha256=abc")))
          .thenReturn(true);
      when(reviewService.createReview(anyString(), anyString(), anyInt(),
          anyString(), anyString(), anyLong()))
          .thenReturn(reviewId);

      mockMvc.perform(post(WEBHOOK_URL)
          .contentType(MediaType.APPLICATION_JSON)
          .header("X-GitHub-Event", "pull_request")
          .header("X-GitHub-Delivery", "delivery-123")
          .header("X-Hub-Signature-256", "sha256=abc")
          .content(validPrPayload()))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.status").value("queued"))
          .andExpect(jsonPath("$.reviewId").value(reviewId.toString()));
    }

    @Test
    @DisplayName("should reject request with invalid HMAC signature")
    void shouldRejectInvalidSignature() throws Exception {
      when(signatureVerifier.isValid(any(byte[].class), eq("sha256=invalid")))
          .thenReturn(false);

      mockMvc.perform(post(WEBHOOK_URL)
          .contentType(MediaType.APPLICATION_JSON)
          .header("X-GitHub-Event", "pull_request")
          .header("X-GitHub-Delivery", "delivery-bad")
          .header("X-Hub-Signature-256", "sha256=invalid")
          .content(validPrPayload()))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.status").value("rejected"))
          .andExpect(jsonPath("$.reason").value("invalid signature"));

      verify(reviewService, never()).createReview(any(), any(), anyInt(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("should reject request with missing signature")
    void shouldRejectMissingSignature() throws Exception {
      when(signatureVerifier.isValid(any(byte[].class), isNull()))
          .thenReturn(false);

      mockMvc.perform(post(WEBHOOK_URL)
          .contentType(MediaType.APPLICATION_JSON)
          .header("X-GitHub-Event", "pull_request")
          .header("X-GitHub-Delivery", "delivery-nosig")
          .content(validPrPayload()))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.status").value("rejected"));

      verify(reviewService, never()).createReview(any(), any(), anyInt(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("should ignore non-PR events with 200")
    void shouldIgnoreNonPrEvents() throws Exception {
      when(signatureVerifier.isValid(any(byte[].class), any()))
          .thenReturn(true);

      mockMvc.perform(post(WEBHOOK_URL)
          .contentType(MediaType.APPLICATION_JSON)
          .header("X-GitHub-Event", "push")
          .header("X-GitHub-Delivery", "delivery-456")
          .content("{}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("ignored"));
    }

    @Test
    @DisplayName("should ignore non-actionable PR events (closed, labeled, etc.)")
    void shouldIgnoreNonActionablePrEvents() throws Exception {
      when(signatureVerifier.isValid(any(byte[].class), any()))
          .thenReturn(true);

      mockMvc.perform(post(WEBHOOK_URL)
          .contentType(MediaType.APPLICATION_JSON)
          .header("X-GitHub-Event", "pull_request")
          .header("X-GitHub-Delivery", "delivery-789")
          .content(closedPrPayload()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reason").value("action not tracked"));
    }
  }

  // ── Test Fixtures ──────────────────────────────────────────────

  private String validPrPayload() {
    return """
        {
          "action": "opened",
          "pull_request": {
            "number": 42,
            "head": { "sha": "abc123def456abc123def456abc123def4560000", "ref": "feature/cool" },
            "base": { "ref": "main" },
            "title": "Add cool feature",
            "changed_files": 3,
            "additions": 100,
            "deletions": 20
          },
          "repository": {
            "full_name": "octocat/hello-world",
            "private": false
          },
          "installation": { "id": 12345 }
        }
        """;
  }

  private String closedPrPayload() {
    return """
        {
          "action": "closed",
          "pull_request": {
            "number": 42,
            "head": { "sha": "abc123def456abc123def456abc123def4560000", "ref": "feature/cool" },
            "base": { "ref": "main" },
            "title": "Add cool feature",
            "changed_files": 3,
            "additions": 100,
            "deletions": 20
          },
          "repository": {
            "full_name": "octocat/hello-world",
            "private": false
          },
          "installation": { "id": 12345 }
        }
        """;
  }
}
