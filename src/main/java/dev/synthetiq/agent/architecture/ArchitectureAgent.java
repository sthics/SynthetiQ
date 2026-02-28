package dev.synthetiq.agent.architecture;

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
 * Architecture agent
 * Specializes in Spring Boot 2->3 migration detection, pattern violations,
 * and modern Java recommendations. Tier: SMART (needs inter-class reasoning).
 */
@Component
public class ArchitectureAgent implements CodeReviewAgent {
    private static final Logger log = LoggerFactory.getLogger(ArchitectureAgent.class);
    private static final int DEFAULT_MAX_CONTEXT_FILES = 15;

    private final AiModelRouter modelRouter;
    private final int maxContextFiles;

    public ArchitectureAgent(AiModelRouter modelRouter, AgentProperties agentProperties) {
        this.modelRouter = modelRouter;
        this.maxContextFiles = agentProperties.architecture() != null
                ? agentProperties.architecture().maxContextFiles()
                : DEFAULT_MAX_CONTEXT_FILES;
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

    /**
     * Ranks files by architecture relevance. Priorities:
     * - Build configs (pom.xml, build.gradle) → migration epicenter
     * - Security configs → high migration impact
     * - Config/configuration classes → framework wiring
     * - javax.* signals in patch → direct migration evidence
     * - Controllers → API layer patterns
     * - Config files (.yml, .properties) → property renames
     */
    @Override
    public List<CodeFile> rankFiles(List<CodeFile> files, int maxFiles) {
        return files.stream()
                .filter(f -> f.isJavaSource() || f.isConfigFile() || "gradle".equals(f.language()))
                .sorted(Comparator.comparingInt(this::scoreForArchitecture).reversed())
                .limit(maxFiles)
                .toList();
    }

    private int scoreForArchitecture(CodeFile file) {
        int score = 0;
        String pathLower = file.path().toLowerCase();
        String patch = file.patch() != null ? file.patch() : "";

        // Build config = migration epicenter
        if (pathLower.contains("pom.xml") || pathLower.contains("build.gradle"))
            score += 100;
        // Security config = high migration impact
        if (pathLower.contains("securityconfig") || pathLower.contains("websecurityconfigureradapter"))
            score += 90;
        // Config/Configuration classes = framework wiring
        if (pathLower.contains("config") && !pathLower.contains("securityconfig"))
            score += 80;
        // Application main class = bootstrap config
        if (pathLower.contains("application.java") || pathLower.endsWith("app.java"))
            score += 70;
        // javax.* in patch = direct migration signal
        if (patch.contains("javax."))
            score += 60;
        // Controllers = API layer
        if (pathLower.contains("controller") || pathLower.contains("restcontroller"))
            score += 50;
        // YAML/properties = property renames
        if (pathLower.endsWith(".yml") || pathLower.endsWith(".yaml") || pathLower.endsWith(".properties"))
            score += 40;
        // Larger diffs = more to review
        score += (file.additions() + file.deletions()) / 10;

        return score;
    }

    @Override
    public AgentAnalysisResult analyze(List<CodeFile> files, String headSha, String repoFullName,
                                       Optional<ProjectGuide> guide) {
        log.info("ArchitectureAgent analyzing {} files for {}", files.size(), repoFullName);
        List<CodeFile> ranked = rankFiles(files, maxContextFiles);
        if (ranked.isEmpty())
            return AgentAnalysisResult.empty();

        log.info("ArchitectureAgent selected top {} of {} files for context", ranked.size(), files.size());

        String prompt = buildPrompt(ranked, repoFullName, guide);
        Instant start = Instant.now();
        AiModelRouter.AiResponse response = modelRouter.route(prompt, AiTier.SMART);

        return new AgentAnalysisResult(response.content(), "Architecture analysis complete.",
                response.tierUsed(), prompt.length() / 4, response.content().length() / 4,
                Duration.between(start, Instant.now()));
    }

    private String buildPrompt(List<CodeFile> files, String repo, Optional<ProjectGuide> guide) {
        String ctx = files.stream()
                .map(f -> "### %s\n```%s\n%s\n```".formatted(f.path(), f.language(), f.patch()))
                .collect(Collectors.joining("\n\n"));
        String base = """
                You are an architecture review agent specializing in Spring Boot. Repository: %s
                Analyze for:
                1. Spring Boot 2->3 migration: javax->jakarta, WebSecurityConfigurerAdapter, property renames
                2. JUnit 4->5: @Test import, @RunWith->@ExtendWith, Assert->Assertions
                3. Modern Java (17+): records, sealed classes, pattern matching, text blocks
                4. Architecture: circular deps, God classes, business logic in controllers
                Respond ONLY with JSON: {"findings":[{"severity":"...","category":"MIGRATION|PATTERN|ARCHITECTURE",
                "file":"...","line":0,"title":"...","description":"...","suggestion":"...","migration_effort":"LOW|MEDIUM|HIGH"}],
                "summary":"..."}""".formatted(repo);
        return PromptUtils.withGuide(base, guide, ctx);
    }
}
