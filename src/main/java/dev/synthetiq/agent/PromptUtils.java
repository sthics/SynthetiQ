package dev.synthetiq.agent;

import dev.synthetiq.domain.valueobject.ProjectGuide;

import java.util.Optional;

/**
 * Shared prompt construction utilities for code review agents.
 */
public final class PromptUtils {

    private PromptUtils() {}

    /**
     * Builds a complete prompt: base instructions + optional project guide + code context.
     */
    public static String withGuide(String basePrompt, Optional<ProjectGuide> guide, String codeContext) {
        var sb = new StringBuilder(basePrompt);
        guide.ifPresent(g -> sb.append("\n\n--- PROJECT GUIDE ---\n")
                               .append(g.content())
                               .append("\n--- END PROJECT GUIDE ---"));
        sb.append("\n\nCode:\n").append(codeContext);
        return sb.toString();
    }
}
