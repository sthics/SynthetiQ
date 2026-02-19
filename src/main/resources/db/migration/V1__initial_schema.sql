-- ═══════════════════════════════════════════════════════════════════
-- V1__initial_schema.sql
-- SynthetiQ initial database schema
-- ═══════════════════════════════════════════════════════════════════
-- Design decisions documented inline.
--
-- We use PostgreSQL-specific features:
--   - UUID as PK (native uuid type, not varchar)
--   - JSONB for flexible agent output
--   - Partial indexes for status-based queries
--   - CHECK constraints for enum validation at DB level
-- ═══════════════════════════════════════════════════════════════════

-- Enable UUID extension (usually pre-installed on RDS)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── Review Requests ──────────────────────────────────────────────
-- Aggregate root for a code review lifecycle.
-- One row per PR webhook event (opened / synchronize).
CREATE TABLE review_requests (
    id                    UUID PRIMARY KEY,
    idempotency_key       VARCHAR(64) NOT NULL UNIQUE,
    repository_full_name  VARCHAR(255) NOT NULL,
    pull_request_number   INTEGER NOT NULL,
    head_sha              CHAR(40) NOT NULL,
    base_branch           VARCHAR(255),
    installation_id       BIGINT NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'RECEIVED'
                          CHECK (status IN ('RECEIVED', 'QUEUED', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),
    version               BIGINT NOT NULL DEFAULT 0,
    retry_count           INTEGER NOT NULL DEFAULT 0,
    error_message         VARCHAR(2000),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at          TIMESTAMP WITH TIME ZONE
);

-- Composite index for "latest reviews for a repo" query (API + dashboard)
CREATE INDEX idx_review_repo_pr ON review_requests (repository_full_name, pull_request_number);

-- Partial index: Only index non-terminal statuses for the "pending work" query.
-- This keeps the index small as completed/failed reviews accumulate.
CREATE INDEX idx_review_active_status ON review_requests (status)
    WHERE status IN ('RECEIVED', 'QUEUED', 'IN_PROGRESS');

CREATE INDEX idx_review_created ON review_requests (created_at DESC);

-- ── Agent Results ────────────────────────────────────────────────
-- One row per agent execution per review.
-- Findings are stored as JSONB for flexible querying.
CREATE TABLE agent_results (
    id                 UUID PRIMARY KEY,
    review_request_id  UUID NOT NULL REFERENCES review_requests(id) ON DELETE CASCADE,
    agent_type         VARCHAR(20) NOT NULL
                       CHECK (agent_type IN ('SECURITY', 'PERFORMANCE', 'ARCHITECTURE', 'REFACTORING', 'REPORT')),
    ai_tier_used       VARCHAR(10) NOT NULL
                       CHECK (ai_tier_used IN ('LOCAL', 'CHEAP', 'SMART')),
    findings           JSONB NOT NULL DEFAULT '{}',
    summary            VARCHAR(4000),
    input_tokens       INTEGER,
    output_tokens       INTEGER,
    duration_ms        BIGINT,
    success            BOOLEAN NOT NULL DEFAULT TRUE,
    error_message      VARCHAR(2000),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_agent_review_id ON agent_results (review_request_id);
CREATE INDEX idx_agent_type ON agent_results (agent_type);

-- GIN index on JSONB findings for queries like:
-- SELECT * FROM agent_results WHERE findings @> '{"findings": [{"severity": "CRITICAL"}]}'
CREATE INDEX idx_agent_findings ON agent_results USING GIN (findings);

-- ── Cost Tracking View ───────────────────────────────────────────
-- Materialized view for the cost dashboard.
-- Refresh periodically (e.g., every hour via pg_cron or app scheduler).
CREATE VIEW v_cost_summary AS
SELECT
    date_trunc('day', ar.created_at) AS day,
    ar.agent_type,
    ar.ai_tier_used,
    COUNT(*) AS request_count,
    SUM(ar.input_tokens) AS total_input_tokens,
    SUM(ar.output_tokens) AS total_output_tokens,
    AVG(ar.duration_ms) AS avg_duration_ms,
    -- Estimated cost calculation (update rates as pricing changes)
    SUM(CASE
        WHEN ar.ai_tier_used = 'LOCAL' THEN 0
        WHEN ar.ai_tier_used = 'CHEAP' THEN (COALESCE(ar.input_tokens, 0) + COALESCE(ar.output_tokens, 0)) * 0.00000004
        WHEN ar.ai_tier_used = 'SMART' THEN (COALESCE(ar.input_tokens, 0) * 0.000003) + (COALESCE(ar.output_tokens, 0) * 0.000015)
        ELSE 0
    END) AS estimated_cost_usd
FROM agent_results ar
WHERE ar.success = TRUE
GROUP BY date_trunc('day', ar.created_at), ar.agent_type, ar.ai_tier_used;
