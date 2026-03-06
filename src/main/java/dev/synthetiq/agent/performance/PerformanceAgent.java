package dev.synthetiq.agent.performance;

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
 * Performance analysis agent: N+1 queries, virtual thread pinning, GC pressure,
 * algorithm complexity, Spring proxy pitfalls, I/O and caching anti-patterns.
 * Tier: CHEAP — structured checklist prompt, pattern-matching heavy.
 */
@Component
public class PerformanceAgent implements CodeReviewAgent {
    private static final Logger log = LoggerFactory.getLogger(PerformanceAgent.class);
    private static final int DEFAULT_MAX_CONTEXT_FILES = 15;

    private final AiModelRouter modelRouter;
    private final int maxContextFiles;

    public PerformanceAgent(AiModelRouter modelRouter, AgentProperties agentProperties) {
        this.modelRouter = modelRouter;
        this.maxContextFiles = agentProperties.performance() != null
                ? agentProperties.performance().maxContextFiles()
                : DEFAULT_MAX_CONTEXT_FILES;
    }

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
        return files.stream().anyMatch(CodeFile::isJavaSource);
    }

    @Override
    public List<CodeFile> rankFiles(List<CodeFile> files, int maxFiles) {
        return files.stream()
                .filter(f -> f.isJavaSource() || f.isConfigFile())
                .sorted(Comparator.comparingInt(this::scoreForPerformance).reversed())
                .limit(maxFiles)
                .toList();
    }

    private int scoreForPerformance(CodeFile file) {
        int score = 0;
        String pathLower = file.path().toLowerCase();
        String patchLower = file.patch() != null ? file.patch().toLowerCase() : "";

        // Repository/DAO — N+1 queries, missing pagination
        if (pathLower.contains("repository") || pathLower.contains("dao"))
            score += 100;
        // Virtual thread pinning signals
        if (patchLower.contains("synchronized") || patchLower.contains("object.wait")
                || patchLower.contains(".lock()") || patchLower.contains(".notify"))
            score += 95;
        // Service classes — business logic hot paths
        if (pathLower.contains("service"))
            score += 85;
        // Loop/stream keywords — algorithm complexity
        if (patchLower.contains(".stream()") || patchLower.contains(".map(")
                || patchLower.contains("for (") || patchLower.contains("while ("))
            score += 80;
        // Spring proxy pitfalls
        if (patchLower.contains("@transactional") || patchLower.contains("@async")
                || patchLower.contains("@cacheable"))
            score += 75;
        // Collection/array allocation — GC pressure
        if (patchLower.contains("new arraylist") || patchLower.contains("new hashmap")
                || patchLower.contains("new byte["))
            score += 70;
        // Controller/handler — request-scoped allocations
        if (pathLower.contains("controller") || pathLower.contains("handler"))
            score += 60;
        // I/O efficiency signals
        if (patchLower.contains("resttemplate") || patchLower.contains("webclient")
                || patchLower.contains("httpclient"))
            score += 55;
        // Config files — cache/pool configuration
        if (pathLower.endsWith(".yml") || pathLower.endsWith(".yaml") || pathLower.endsWith(".properties"))
            score += 40;
        // Larger diffs = more surface area
        score += (file.additions() + file.deletions()) / 10;

        return score;
    }

    @Override
    public AgentAnalysisResult analyze(List<CodeFile> files, String headSha, String repoFullName,
                                       Optional<ProjectGuide> guide) {
        log.info("PerformanceAgent analyzing {} files for {}", files.size(), repoFullName);
        List<CodeFile> ranked = rankFiles(files, maxContextFiles);
        if (ranked.isEmpty())
            return AgentAnalysisResult.empty();

        log.info("PerformanceAgent selected top {} of {} files for context", ranked.size(), files.size());

        String prompt = buildPrompt(ranked, repoFullName, guide);
        Instant start = Instant.now();
        AiModelRouter.AiResponse response = modelRouter.route(prompt, AiTier.CHEAP);

        return new AgentAnalysisResult(response.content(), "Performance analysis complete.",
                response.tierUsed(), prompt.length() / 4, response.content().length() / 4,
                Duration.between(start, Instant.now()));
    }

    private String buildPrompt(List<CodeFile> files, String repo, Optional<ProjectGuide> guide) {
        String ctx = files.stream()
                .map(f -> "### %s\n```%s\n%s\n```".formatted(f.path(), f.language(), f.patch()))
                .collect(Collectors.joining("\n\n"));
        String base = """
                You are a performance code review agent for repository: %s
                Analyze the code changes for these specific performance patterns:

                DATABASE:
                - N+1 queries: repository/DB calls inside loops or stream operations
                - Missing pagination: findAll() without Pageable, unbounded queries
                - Eager loading: FetchType.EAGER on @ManyToOne/@OneToMany

                VIRTUAL THREADS (JDK 21+):
                - synchronized methods/blocks containing I/O (HTTP calls, DB access)
                - Object.wait()/notify() in async contexts
                - ThreadLocal misuse in virtual thread contexts

                ALGORITHMS:
                - Nested loops over collections (O(n^2) or worse)
                - Stream operations that should be short-circuited (findFirst vs collect+get)
                - Repeated collection traversals that could be single-pass

                MEMORY/GC PRESSURE:
                - String concatenation in loops (use StringBuilder)
                - Auto-boxing in hot loops (Integer vs int)
                - Oversized allocations (large byte[] on heap, suggest ByteBuffer.allocateDirect)
                - ArrayList/HashMap without initial capacity in known-size scenarios

                CONCURRENCY:
                - Lock contention on shared mutable state
                - Blocking calls without timeout
                - Thread-unsafe collection usage in concurrent contexts

                I/O:
                - Unbuffered streams
                - new RestTemplate()/new HttpClient() per request (no connection pooling)
                - Synchronous HTTP in hot paths

                CACHING:
                - Repeated identical DB/API calls that could be cached
                - Unbounded caches (missing eviction/TTL)

                SPRING FRAMEWORK:
                - @Transactional on private methods (proxy bypass)
                - @Async without custom Executor bean (SimpleAsyncTaskExecutor)
                - @Cacheable on methods with side effects

                Respond ONLY with JSON: {"findings":[{"severity":"CRITICAL|HIGH|MEDIUM|LOW",\
                "category":"DATABASE|VIRTUAL_THREADS|ALGORITHM|MEMORY|CONCURRENCY|IO|CACHING|SPRING",\
                "file":"...","line":0,"title":"...","description":"...",\
                "suggestion":"...","impact":"HIGH|MEDIUM|LOW",\
                "effort":"LOW|MEDIUM|HIGH"}],\
                "summary":"..."}""".formatted(repo);
        return PromptUtils.withGuide(base, guide, ctx);
    }
}
