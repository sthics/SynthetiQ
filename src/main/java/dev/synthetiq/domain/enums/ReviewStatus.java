package dev.synthetiq.domain.enums;

/**
 * Lifecycle: RECEIVED → QUEUED → IN_PROGRESS → COMPLETED | FAILED
 */
public enum ReviewStatus {
    RECEIVED, QUEUED, IN_PROGRESS, COMPLETED, FAILED
}
