package dev.synthetiq.domain.entity;

import dev.synthetiq.domain.enums.ReviewStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for a code review request.
 *
 * Design: UUID PK (idempotent creation from webhook delivery ID),
 * optimistic locking (@Version) for concurrent agent updates,
 * factory method for enforcing invariants on creation.
 */
@Entity
@Table(name = "review_requests", indexes = {
        @Index(name = "idx_review_repo_pr", columnList = "repository_full_name, pull_request_number"),
        @Index(name = "idx_review_status", columnList = "status"),
        @Index(name = "idx_review_created", columnList = "created_at")
})
public class ReviewRequest {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "idempotency_key", unique = true, nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "repository_full_name", nullable = false)
    private String repositoryFullName;

    @Column(name = "pull_request_number", nullable = false)
    private Integer pullRequestNumber;

    @Column(name = "head_sha", nullable = false, length = 40)
    private String headSha;

    @Column(name = "base_branch")
    private String baseBranch;

    @Column(name = "installation_id", nullable = false)
    private Long installationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status;

    @Version
    private Long version;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "reviewRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<AgentResult> agentResults = new ArrayList<>();

    protected ReviewRequest() {
    }

    public static ReviewRequest create(String idempotencyKey, String repoFullName,
            int prNumber, String headSha,
            String baseBranch, long installationId) {
        ReviewRequest r = new ReviewRequest();
        r.id = UUID.randomUUID();
        r.idempotencyKey = idempotencyKey;
        r.repositoryFullName = repoFullName;
        r.pullRequestNumber = prNumber;
        r.headSha = headSha;
        r.baseBranch = baseBranch;
        r.installationId = installationId;
        r.status = ReviewStatus.RECEIVED;
        r.createdAt = Instant.now();
        r.updatedAt = Instant.now();
        return r;
    }

    public void markQueued() {
        transition(ReviewStatus.RECEIVED);
        this.status = ReviewStatus.QUEUED;
        touch();
    }

    public void markInProgress() {
        transitionFrom(ReviewStatus.RECEIVED, ReviewStatus.QUEUED);
        this.status = ReviewStatus.IN_PROGRESS;
        touch();
    }

    public void markCompleted() {
        this.status = ReviewStatus.COMPLETED;
        this.completedAt = Instant.now();
        touch();
    }

    public void markFailed(String error) {
        this.status = ReviewStatus.FAILED;
        this.errorMessage = error;
        touch();
    }

    public void incrementRetry() {
        this.retryCount++;
        touch();
    }

    public void addAgentResult(AgentResult result) {
        agentResults.add(result);
        result.setReviewRequest(this);
    }

    private void transition(ReviewStatus expected) {
        if (this.status != expected)
            throw new IllegalStateException("Expected %s but was %s".formatted(expected, status));
    }

    private void transitionFrom(ReviewStatus... allowedPredecessors) {
        for (ReviewStatus allowed : allowedPredecessors) {
            if (this.status == allowed) return;
        }
        throw new IllegalStateException(
                "Expected one of %s but was %s".formatted(java.util.Arrays.toString(allowedPredecessors), status));
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    // Getters
    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRepositoryFullName() {
        return repositoryFullName;
    }

    public Integer getPullRequestNumber() {
        return pullRequestNumber;
    }

    public String getHeadSha() {
        return headSha;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public Long getInstallationId() {
        return installationId;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public List<AgentResult> getAgentResults() {
        return List.copyOf(agentResults);
    }
}
