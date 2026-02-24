package dev.synthetiq.domain.entity;

import dev.synthetiq.domain.enums.AgentType;
import dev.synthetiq.domain.enums.AiTier;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Stores one agent's analysis output per review. JSONB findings column
 * for flexible querying across heterogeneous agent output schemas.
 */
@Entity
@Table(name = "agent_results", indexes = {
        @Index(name = "idx_agent_review_id", columnList = "review_request_id"),
        @Index(name = "idx_agent_type", columnList = "agent_type")
})
public class AgentResult {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_request_id", nullable = false)
    private ReviewRequest reviewRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_type", nullable = false, length = 20)
    private AgentType agentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_tier_used", nullable = false, length = 10)
    private AiTier aiTierUsed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "findings", columnDefinition = "jsonb", nullable = false)
    private String findings;

    @Column(name = "summary", length = 4000)
    private String summary;
    @Column(name = "input_tokens")
    private Integer inputTokens;
    @Column(name = "output_tokens")
    private Integer outputTokens;
    @Column(name = "duration_ms")
    private Long durationMs;
    @Column(name = "success", nullable = false)
    private boolean success;
    @Column(name = "error_message", length = 2000)
    private String errorMessage;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentResult() {
    }

    public static AgentResult success(AgentType type, AiTier tier, String findings,
            String summary, int inTokens, int outTokens, Duration duration) {
        AgentResult r = new AgentResult();
        r.id = UUID.randomUUID();
        r.agentType = type;
        r.aiTierUsed = tier;
        r.findings = findings;
        r.summary = summary;
        r.inputTokens = inTokens;
        r.outputTokens = outTokens;
        r.durationMs = duration.toMillis();
        r.success = true;
        r.createdAt = Instant.now();
        return r;
    }

    public static AgentResult failure(AgentType type, String error) {
        AgentResult r = new AgentResult();
        r.id = UUID.randomUUID();
        r.agentType = type;
        r.aiTierUsed = AiTier.LOCAL;
        r.findings = "{}";
        r.success = false;
        r.errorMessage = error;
        r.createdAt = Instant.now();
        return r;
    }

    void setReviewRequest(ReviewRequest rr) {
        this.reviewRequest = rr;
    }

    public UUID getId() {
        return id;
    }

    public AgentType getAgentType() {
        return agentType;
    }

    public AiTier getAiTierUsed() {
        return aiTierUsed;
    }

    public String getFindings() {
        return findings;
    }

    public String getSummary() {
        return summary;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
