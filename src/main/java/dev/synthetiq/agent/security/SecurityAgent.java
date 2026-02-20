package dev.synthetiq.agent.security;

import dev.synthetiq.agent.AgentAnalysisResult;
import dev.synthetiq.agent.CodeReviewAgent;
import dev.synthetiq.config.AgentProperties;
import dev.synthetiq.domain.enums.AgentType;
import dev.synthetiq.domain.enums.AiTier;
import dev.synthetiq.domain.valueobject.CodeFile;
import dev.synthetiq.infrastructure.ai.AiModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Security scanning agent: secrets, SQLi, XSS, SSRF, Spring Security misconfig.
 * Tier: CHEAP — pattern-matching heavy, smaller models handle well.
 */
@Component
public class SecurityAgent implements CodeReviewAgent {
    private static final Logger log = LoggerFactory.getLogger(SecurityAgent.class);
    private static final int DEFAULT_MAX_CONTEXT_FILES = 15;

    private final AiModelRouter modelRouter;
    private final int maxContextFiles;

    public SecurityAgent(AiModelRouter modelRouter, AgentProperties agentProperties) {
        this.modelRouter = modelRouter;
        this.maxContextFiles = agentProperties.security() != null
                ? agentProperties.security().maxContextFiles()
                : DEFAULT_MAX_CONTEXT_FILES;
    }

    @Override
    public AgentType getType() {
        return AgentType.SECURITY;
    }

    @Override
    public AiTier getMinimumTier() {
        return AiTier.CHEAP;
    }

    @Override
    public boolean supports(List<CodeFile> files) {
        return files.stream().anyMatch(f -> f.isJavaSource() || f.isConfigFile());
    }

    /**
     * Ranks files by security relevance. Priorities:
     * - Security/Auth/Filter classes → core security layer
     * - Patch contains password/secret/token/credential → hardcoded secrets
     * - Controllers/Handlers → input handling = injection risk
     * - SQL keywords in patch → SQLi signal
     * - Repository/DAO classes → data layer
     * - Config files → security misconfig
     * - XSS signals in patch → client-side risk
     */
    @Override
    public List<CodeFile> rankFiles(List<CodeFile> files, int maxFiles) {
        return files.stream()
                .filter(f -> f.isJavaSource() || f.isConfigFile())
                .sorted(Comparator.comparingInt(this::scoreForSecurity).reversed())
                .limit(maxFiles)
                .toList();
    }

    private int scoreForSecurity(CodeFile file) {
        int score = 0;
        String pathLower = file.path().toLowerCase();
        String patchLower = file.patch() != null ? file.patch().toLowerCase() : "";

        // Core security layer
        if (pathLower.contains("security") || pathLower.contains("auth") || pathLower.contains("filter"))
            score += 100;
        // Hardcoded secrets red flag
        if (patchLower.contains("password") || patchLower.contains("secret")
                || patchLower.contains("token") || patchLower.contains("credential"))
            score += 90;
        // Input handling = injection risk
        if (pathLower.contains("controller") || pathLower.contains("handler") || pathLower.contains("endpoint"))
            score += 80;
        // SQLi signal
        if (patchLower.contains("select ") || patchLower.contains("insert ")
                || patchLower.contains("executequery") || patchLower.contains("preparestatement"))
            score += 70;
        // Data layer
        if (pathLower.contains("repository") || pathLower.contains("dao"))
            score += 60;
        // Security misconfig in config files
        if (pathLower.contains("config") || pathLower.endsWith(".yml") || pathLower.endsWith(".properties"))
            score += 50;
        // XSS signal
        if (patchLower.contains("innerhtml") || patchLower.contains("eval(") || patchLower.contains("document.write"))
            score += 40;
        // Larger diffs = more surface area
        score += (file.additions() + file.deletions()) / 10;

        return score;
    }

    @Override
    public AgentAnalysisResult analyze(List<CodeFile> files, String headSha, String repoFullName) {
        log.info("SecurityAgent analyzing {} files for {}", files.size(), repoFullName);
        List<CodeFile> ranked = rankFiles(files, maxContextFiles);
        if (ranked.isEmpty())
            return AgentAnalysisResult.empty();

        log.info("SecurityAgent selected top {} of {} files for context", ranked.size(), files.size());

        String prompt = buildPrompt(ranked, repoFullName);
        Instant start = Instant.now();
        AiModelRouter.AiResponse response = modelRouter.route(prompt, AiTier.CHEAP);

        return new AgentAnalysisResult(response.content(), "Security analysis complete.",
                response.tierUsed(), prompt.length() / 4, response.content().length() / 4,
                Duration.between(start, Instant.now()));
    }

    private String buildPrompt(List<CodeFile> files, String repo) {
        String ctx = files.stream()
                .map(f -> "### %s\n```%s\n%s\n```".formatted(f.path(), f.language(), f.patch()))
                .collect(Collectors.joining("\n\n"));
        return """
                You are a security code review agent for repository: %s
                Analyze for: hardcoded secrets, SQL injection, XSS, SSRF, unsafe deserialization,
                Spring Security misconfigurations, dependency vulnerabilities.
                Respond ONLY with JSON: {"findings":[{"severity":"CRITICAL|HIGH|MEDIUM|LOW","category":"...",
                "file":"...","line":0,"title":"...","description":"...","suggestion":"..."}],"summary":"..."}
                Code:\n%s""".formatted(repo, ctx);
    }
}
