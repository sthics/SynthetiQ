package dev.synthetiq.agent.orchestrator;

/**
 * Represents an inline review comment to post on a specific line of a PR diff.
 */
public record InlineComment(String path, int line, String body) {
}
