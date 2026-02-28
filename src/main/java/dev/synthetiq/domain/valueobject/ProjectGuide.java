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
        int cut = rawContent.lastIndexOf('\n', MAX_BYTES);
        if (cut <= 0) {
            cut = MAX_BYTES;
        }
        return Optional.of(new ProjectGuide(rawContent.substring(0, cut + 1), true));
    }
}
