package dev.synthetiq.domain.valueobject;

import java.util.Optional;

/**
 * Immutable representation of a repository's SYNTHETIQ.md project guide.
 * Soft-capped at 8KB to protect token budgets.
 */
public record ProjectGuide(String content, boolean truncated) {

    private static final int MAX_BYTES = 8192;

    public static Optional<ProjectGuide> of(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return Optional.empty();
        }
        if (rawContent.length() <= MAX_BYTES) {
            return Optional.of(new ProjectGuide(rawContent, false));
        }
        int newlinePos = rawContent.lastIndexOf('\n', MAX_BYTES);
        int endIndex = (newlinePos <= 0) ? MAX_BYTES : newlinePos + 1;
        return Optional.of(new ProjectGuide(rawContent.substring(0, endIndex), true));
    }
}
