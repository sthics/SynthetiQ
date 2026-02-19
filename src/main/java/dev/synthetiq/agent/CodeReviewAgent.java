package dev.synthetiq.agent;

import dev.synthetiq.domain.enums.AgentType;
import dev.synthetiq.domain.enums.AiTier;
import dev.synthetiq.domain.valueobject.CodeFile;
import java.util.List;

/**
 * Strategy pattern contract for specialized analysis agents.
 * Agents are auto-discovered via List<CodeReviewAgent> injection.
 * Adding a new agent = implement this + @Component. No registration needed.
 */
public interface CodeReviewAgent {
    AgentType getType();
    AiTier getMinimumTier();
    boolean supports(List<CodeFile> files);
    AgentAnalysisResult analyze(List<CodeFile> files, String headSha, String repoFullName);
}
