package dev.synthetiq.agent.architecture;

import dev.synthetiq.agent.AgentAnalysisResult;
import dev.synthetiq.agent.CodeReviewAgent;
import dev.synthetiq.domain.enums.AgentType;
import dev.synthetiq.domain.enums.AiTier;
import dev.synthetiq.domain.valueobject.CodeFile;
import dev.synthetiq.infrastructure.ai.AiModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Architecture agent
 * Specializes in Spring Boot 2->3 migration detection, pattern violations,
 * and modern Java recommendations. Tier: SMART (needs inter-class reasoning).
 */
@Component
public class ArchitectureAgent implements CodeReviewAgent {
    private static final Logger log = LoggerFactory.getLogger(ArchitectureAgent.class);
    private final AiModelRouter modelRouter;

    public ArchitectureAgent(AiModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }

    @Override
    public AgentType getType() {
        return AgentType.ARCHITECTURE;
    }

    @Override
    public AiTier getMinimumTier() {
        return AiTier.SMART;
    }

    @Override
    public boolean supports(List<CodeFile> files) {
        return files.stream().anyMatch(f -> f.isJavaSource() || f.isConfigFile() || "gradle".equals(f.language()));
    }

    @Override
    public AgentAnalysisResult analyze(List<CodeFile> files, String headSha, String repoFullName) {
        log.info("ArchitectureAgent analyzing {} files for {}", files.size(), repoFullName);
        List<CodeFile> relevant = files.stream()
                .filter(f -> f.isJavaSource() || f.isConfigFile() || "gradle".equals(f.language())).toList();
        if (relevant.isEmpty())
            return AgentAnalysisResult.empty();

        String prompt = buildPrompt(relevant, repoFullName);
        Instant start = Instant.now();
        AiModelRouter.AiResponse response = modelRouter.route(prompt, AiTier.SMART);

        return new AgentAnalysisResult(response.content(), "Architecture analysis complete.",
                response.tierUsed(), prompt.length() / 4, response.content().length() / 4,
                Duration.between(start, Instant.now()));
    }

    private String buildPrompt(List<CodeFile> files, String repo) {
        String ctx = files.stream()
                .map(f -> "### %s\n```%s\n%s\n```".formatted(f.path(), f.language(), f.patch()))
                .collect(Collectors.joining("\n\n"));
        return """
                You are an architecture review agent specializing in Spring Boot. Repository: %s
                Analyze for:
                1. Spring Boot 2->3 migration: javax->jakarta, WebSecurityConfigurerAdapter, property renames
                2. JUnit 4->5: @Test import, @RunWith->@ExtendWith, Assert->Assertions
                3. Modern Java (17+): records, sealed classes, pattern matching, text blocks
                4. Architecture: circular deps, God classes, business logic in controllers
                Respond ONLY with JSON: {"findings":[{"severity":"...","category":"MIGRATION|PATTERN|ARCHITECTURE",
                "file":"...","line":0,"title":"...","description":"...","suggestion":"...","migration_effort":"LOW|MEDIUM|HIGH"}],
                "summary":"..."}
                Code:\n%s"""
                .formatted(repo, ctx);
    }
}
