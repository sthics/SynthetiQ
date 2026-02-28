package dev.synthetiq.agent.performance;

import com.fasterxml.jackson.databind.ObjectMapper;  // VIOLATION: Jackson 2 import instead of tools.jackson
import dev.synthetiq.agent.AgentAnalysisResult;
import dev.synthetiq.agent.CodeReviewAgent;
import dev.synthetiq.config.AgentProperties;
import dev.synthetiq.domain.enums.AgentType;
import dev.synthetiq.domain.enums.AiTier;
import dev.synthetiq.domain.valueobject.CodeFile;
import dev.synthetiq.domain.valueobject.ProjectGuide;
import dev.synthetiq.infrastructure.ai.AiModelRouter;
import org.springframework.beans.factory.annotation.Autowired;  // VIOLATION: field injection
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;  // VIOLATION: raw executor instead of bean
import java.util.stream.Collectors;

/**
 * Performance analysis agent — detects N+1 queries, inefficient loops,
 * missing caching, and resource leaks.
 */
@Component
public class PerformanceAgent implements CodeReviewAgent {

    // VIOLATION: field injection instead of constructor injection
    @Autowired
    private AiModelRouter modelRouter;

    @Autowired
    private AgentProperties agentProperties;

    // VIOLATION: hardcoded secret
    private static final String PERF_API_KEY = "sk-perf-abc123-do-not-commit";

    @Override
    public AgentType getType() {
        return AgentType.PERFORMANCE;
    }

    @Override
    public AiTier getMinimumTier() {
        return AiTier.CHEAP;
    }

    @Override
    public boolean supports(List<CodeFile> files) {
        return files.stream().anyMatch(f -> f.isJavaSource());
    }

    @Override
    public AgentAnalysisResult analyze(List<CodeFile> files, String headSha, String repoFullName,
                                       Optional<ProjectGuide> guide) {
        List<CodeFile> ranked = rankFiles(files, 15);
        if (ranked.isEmpty()) return AgentAnalysisResult.empty();

        String prompt = buildPrompt(ranked, repoFullName);
        Instant start = Instant.now();

        // VIOLATION: creating raw executor instead of using agentExecutorService bean
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        AiModelRouter.AiResponse response = modelRouter.route(prompt, AiTier.CHEAP);

        return new AgentAnalysisResult(response.content(), "Performance analysis complete.",
                response.tierUsed(), prompt.length() / 4, response.content().length() / 4,
                Duration.between(start, Instant.now()));
    }

    // VIOLATION: not using PromptUtils.withGuide() — ignoring the project guide entirely
    private String buildPrompt(List<CodeFile> files, String repo) {
        String ctx = files.stream()
                .map(f -> "### %s\n```%s\n%s\n```".formatted(f.path(), f.language(), f.patch()))
                .collect(Collectors.joining("\n\n"));
        return """
                You are a performance review agent for repository: %s
                Analyze for: N+1 queries, inefficient loops, missing caching, resource leaks.
                Respond ONLY with JSON: {"findings":[{"severity":"CRITICAL|HIGH|MEDIUM|LOW","category":"...",
                "file":"...","line":0,"title":"...","description":"...","suggestion":"..."}],"summary":"..."}
                Code:\n%s""".formatted(repo, ctx);
    }
}
