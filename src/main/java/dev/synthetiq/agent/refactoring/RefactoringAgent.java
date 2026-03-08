package dev.synthetiq.agent.refactoring;

import dev.synthetiq.agent.AgentAnalysisResult;
import dev.synthetiq.agent.CodeReviewAgent;
import dev.synthetiq.agent.PromptUtils;
import dev.synthetiq.config.AgentProperties;
import dev.synthetiq.domain.enums.AgentType;
import dev.synthetiq.domain.enums.AiTier;
import dev.synthetiq.domain.valueobject.CodeFile;
import dev.synthetiq.domain.valueobject.ProjectGuide;
import dev.synthetiq.infrastructure.ai.AiModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Refactoring agent: dead code, duplication, complexity, naming, SOLID violations,
 * extract method/class opportunities, magic values.
 * Tier: CHEAP — structured checklist prompt, pattern-matching heavy.
 */
@Component
public class RefactoringAgent implements CodeReviewAgent {
    private static final Logger log = LoggerFactory.getLogger(RefactoringAgent.class);
    private static final int DEFAULT_MAX_CONTEXT_FILES = 15;

    private final AiModelRouter modelRouter;
    private final int maxContextFiles;

    public RefactoringAgent(AiModelRouter modelRouter, AgentProperties agentProperties) {
        this.modelRouter = modelRouter;
        this.maxContextFiles = agentProperties.refactoring() != null
                ? agentProperties.refactoring().maxContextFiles()
                : DEFAULT_MAX_CONTEXT_FILES;
    }

    @Override
    public AgentType getType() {
        return AgentType.REFACTORING;
    }

    @Override
    public AiTier getMinimumTier() {
        return AiTier.CHEAP;
    }

    @Override
    public boolean supports(List<CodeFile> files) {
        return files.stream().anyMatch(CodeFile::isJavaSource);
    }

    @Override
    public List<CodeFile> rankFiles(List<CodeFile> files, int maxFiles) {
        return files.stream()
                .filter(CodeFile::isJavaSource)
                .sorted(Comparator.comparingInt(this::scoreForRefactoring).reversed())
                .limit(maxFiles)
                .toList();
    }

    private int scoreForRefactoring(CodeFile file) {
        int score = 0;
        String pathLower = file.path().toLowerCase();
        String patchLower = file.patch() != null ? file.patch().toLowerCase() : "";

        // Large methods — high additions signal long/complex code
        if (file.additions() > 50)
            score += 100;
        // Service classes — business logic, highest refactoring value
        if (pathLower.contains("service"))
            score += 90;
        // Deep nesting signals
        int nestingSignals = countOccurrences(patchLower, "if (")
                + countOccurrences(patchLower, "for (")
                + countOccurrences(patchLower, "while (");
        if (nestingSignals >= 3)
            score += 85;
        // Duplication signals — repeated patterns in patch
        if (patchLower.contains("todo") || patchLower.contains("fixme") || patchLower.contains("hack"))
            score += 80;
        // Controller classes — fat controllers = responsibility leaks
        if (pathLower.contains("controller") || pathLower.contains("handler"))
            score += 70;
        // Polymorphism opportunities
        if (patchLower.contains("instanceof") || patchLower.contains("(string)") || patchLower.contains("(int)")
                || patchLower.contains("getclass()"))
            score += 65;
        // Magic numbers / hardcoded strings
        if (patchLower.matches("(?s).*[^a-z]\\d{3,}[^a-z].*") || patchLower.contains("\"http"))
            score += 60;
        // Utility/helper classes — abstraction sprawl
        if (pathLower.contains("util") || pathLower.contains("helper"))
            score += 50;
        // Larger diffs = more surface area
        score += (file.additions() + file.deletions()) / 10;

        return score;
    }

    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    @Override
    public AgentAnalysisResult analyze(List<CodeFile> files, String headSha, String repoFullName,
            Optional<ProjectGuide> guide) {
        log.info("RefactoringAgent analyzing {} files for {}", files.size(), repoFullName);
        List<CodeFile> ranked = rankFiles(files, maxContextFiles);
        if (ranked.isEmpty())
            return AgentAnalysisResult.empty();

        log.info("RefactoringAgent selected top {} of {} files for context", ranked.size(), files.size());

        String prompt = buildPrompt(ranked, repoFullName, guide);
        Instant start = Instant.now();
        AiModelRouter.AiResponse response = modelRouter.route(prompt, AiTier.CHEAP);

        return new AgentAnalysisResult(response.content(), "Refactoring analysis complete.",
                response.tierUsed(), prompt.length() / 4, response.content().length() / 4,
                Duration.between(start, Instant.now()));
    }

    private String buildPrompt(List<CodeFile> files, String repo, Optional<ProjectGuide> guide) {
        String ctx = files.stream()
                .map(f -> "### %s\n```%s\n%s\n```".formatted(f.path(), f.language(), f.patch()))
                .collect(Collectors.joining("\n\n"));
        String base = """
                You are a refactoring code review agent for repository: %s
                Analyze the code changes for these specific refactoring opportunities:

                DEAD CODE:
                - Unused variables, parameters, imports, or private methods
                - Unreachable branches (always-true/false conditions)
                - Commented-out code that should be removed

                DUPLICATION:
                - Copy-pasted logic across methods or classes
                - Similar code blocks that could share a common abstraction
                - Repeated validation/transformation patterns

                COMPLEXITY:
                - Methods longer than ~30 lines (extract method candidates)
                - Deep nesting (3+ levels of if/for/while)
                - Long boolean expressions that should be named predicates
                - Switch/if-else chains that could use polymorphism or pattern matching

                NAMING:
                - Misleading or ambiguous variable/method/class names
                - Inconsistent naming conventions within the change
                - Single-letter variables outside of trivial loops

                SOLID VIOLATIONS:
                - Classes with too many responsibilities (Single Responsibility)
                - Fat interfaces that should be split (Interface Segregation)
                - Concrete class dependencies that should be injected (Dependency Inversion)

                EXTRACT OPPORTUNITIES:
                - Inline logic that should be a named method
                - Related fields/methods that belong in a separate class
                - Lambda expressions complex enough to warrant a named method

                MAGIC VALUES:
                - Hardcoded numbers without explanation
                - Hardcoded strings (URLs, messages) that should be constants or config
                - Repeated literal values that should be a single constant

                For each finding, set "line" to the exact line number from the patch where the issue occurs (0 if file-level).
                Set "suggested_code" to the exact replacement code for the affected lines (empty string if the fix is conceptual).
                Set "suggestion_type" to "replacement" if suggested_code is a drop-in replacement, or "conceptual" if it shows a general approach.
                Respond ONLY with JSON: {"findings":[{"severity":"CRITICAL|HIGH|MEDIUM|LOW",\
                "category":"DEAD_CODE|DUPLICATION|COMPLEXITY|NAMING|SOLID|EXTRACT_METHOD|MAGIC_VALUES",\
                "file":"...","line":0,"title":"...","description":"...",\
                "suggestion":"...","suggested_code":"...","suggestion_type":"replacement|conceptual",\
                "impact":"HIGH|MEDIUM|LOW",\
                "effort":"LOW|MEDIUM|HIGH"}],\
                "summary":"..."}""".formatted(repo);
        return PromptUtils.withGuide(base, guide, ctx);
    }
}
