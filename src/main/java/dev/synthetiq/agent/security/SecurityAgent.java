package dev.synthetiq.agent.security;

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
 * Security scanning agent: secrets, SQLi, XSS, SSRF, Spring Security misconfig.
 * Tier: CHEAP — pattern-matching heavy, smaller models handle well.
 */
@Component
public class SecurityAgent implements CodeReviewAgent {
    private static final Logger log = LoggerFactory.getLogger(SecurityAgent.class);
    private final AiModelRouter modelRouter;

    public SecurityAgent(AiModelRouter modelRouter) { this.modelRouter = modelRouter; }

    @Override public AgentType getType() { return AgentType.SECURITY; }
    @Override public AiTier getMinimumTier() { return AiTier.CHEAP; }
    @Override public boolean supports(List<CodeFile> files) {
        return files.stream().anyMatch(f -> f.isJavaSource() || f.isConfigFile());
    }

    @Override
    public AgentAnalysisResult analyze(List<CodeFile> files, String headSha, String repoFullName) {
        log.info("SecurityAgent analyzing {} files for {}", files.size(), repoFullName);
        List<CodeFile> relevant = files.stream()
                .filter(f -> f.isJavaSource() || f.isConfigFile()).toList();
        if (relevant.isEmpty()) return AgentAnalysisResult.empty();

        String prompt = buildPrompt(relevant, repoFullName);
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
