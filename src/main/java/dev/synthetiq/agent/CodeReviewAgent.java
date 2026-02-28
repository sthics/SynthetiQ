package dev.synthetiq.agent;

import dev.synthetiq.domain.enums.AgentType;
import dev.synthetiq.domain.enums.AiTier;
import dev.synthetiq.domain.valueobject.CodeFile;
import dev.synthetiq.domain.valueobject.ProjectGuide;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Strategy pattern contract for specialized analysis agents.
 * Agents are auto-discovered via List<CodeReviewAgent> injection.
 * Adding a new agent = implement this + @Component. No registration needed.
 */
public interface CodeReviewAgent {
    AgentType getType();

    AiTier getMinimumTier();

    boolean supports(List<CodeFile> files);

    AgentAnalysisResult analyze(List<CodeFile> files, String headSha, String repoFullName,
                            Optional<ProjectGuide> guide);

    /**
     * Ranks files by domain-specific relevance and caps at maxFiles.
     * Default: sort by estimated token count descending (bigger diffs = more
     * context).
     * Agents should override this with domain-specific scoring.
     *
     * @param files    all files from the PR (unfiltered)
     * @param maxFiles maximum number of files to include in the prompt
     * @return ranked, capped list of files most relevant to this agent
     */
    default List<CodeFile> rankFiles(List<CodeFile> files, int maxFiles) {
        return files.stream()
                .sorted(Comparator.comparingInt(CodeFile::estimateTokens).reversed())
                .limit(maxFiles)
                .toList();
    }
}
