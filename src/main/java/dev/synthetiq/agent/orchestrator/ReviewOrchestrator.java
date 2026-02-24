package dev.synthetiq.agent.orchestrator;

import dev.synthetiq.agent.AgentAnalysisResult;
import dev.synthetiq.agent.CodeReviewAgent;
import dev.synthetiq.config.AgentProperties;
import dev.synthetiq.config.AiProperties;
import dev.synthetiq.domain.entity.AgentResult;
import dev.synthetiq.domain.entity.ReviewRequest;
import dev.synthetiq.domain.enums.AgentType;
import dev.synthetiq.domain.enums.ReviewStatus;
import dev.synthetiq.domain.valueobject.CodeFile;
import dev.synthetiq.infrastructure.github.GitHubApiClient;
import dev.synthetiq.repository.ReviewRequestRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates the multi-agent code review pipeline.
 *
 * <p>This is the brain of SynthetiQ. The orchestration flow:
 *
 * <pre>
 *  1. Receive review task (from SQS listener)
 *  2. Fetch PR files from GitHub
 *  3. Determine which agents are applicable
 *  4. Fan out analysis to agents IN PARALLEL (virtual threads)
 *  5. Await all results (with timeout)
 *  6. Aggregate findings into a unified report
 *  7. Post the review comment on the PR
 *  8. Update review status to COMPLETED
 * </pre>
 *
 * <p>Design decisions:
 * <ul>
 *   <li><b>Fan-out/fan-in over sequential</b>: Agents are independent — security
 *       analysis doesn't depend on architecture analysis. Running them in parallel
 *       with CompletableFuture reduces total latency from sum(agents) to max(agents).</li>
 *   <li><b>Virtual thread executor</b>: Each agent call blocks on I/O (AI model + GitHub).
 *       Virtual threads handle this without platform thread exhaustion.</li>
 *   <li><b>Partial success</b>: If one agent fails, we still post results from
 *       the others. A review with 3/4 agents is better than no review.</li>
 *   <li><b>Timeout per agent</b>: 60-second cap. If an AI model is slow, we skip
 *       that agent rather than blocking the entire pipeline.</li>
 *   <li><b>Split transactions</b>: The method is NOT @Transactional as a whole.
 *       Instead, transactional boundaries are scoped to beginReview (mark IN_PROGRESS)
 *       and completeReview (persist results). This avoids accessing detached entities
 *       from async agent threads running outside the Hibernate session.</li>
 * </ul>
 */
@Component
public class ReviewOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrator.class);

    private final Duration agentTimeout;
    private final List<CodeReviewAgent> agents;
    private final Map<AgentType, CodeReviewAgent> agentMap;
    private final AgentProperties agentProperties;
    private final AiProperties aiProperties;
    private final GitHubApiClient gitHubClient;
    private final ReviewRequestRepository reviewRepository;
    private final ExecutorService agentExecutor;
    private final Timer orchestrationTimer;

    public ReviewOrchestrator(List<CodeReviewAgent> agents,
                              AgentProperties agentProperties,
                              AiProperties aiProperties,
                              GitHubApiClient gitHubClient,
                              ReviewRequestRepository reviewRepository,
                              @Qualifier("agentExecutorService") ExecutorService agentExecutor,
                              MeterRegistry meterRegistry,
                              @Value("${synthetiq.review.agent-timeout-seconds:120}") long agentTimeoutSeconds) {
        this.agentTimeout = Duration.ofSeconds(agentTimeoutSeconds);
        this.agents = agents;
        this.agentMap = agents.stream()
                .collect(Collectors.toMap(CodeReviewAgent::getType, Function.identity()));
        this.agentProperties = agentProperties;
        this.aiProperties = aiProperties;
        this.gitHubClient = gitHubClient;
        this.reviewRepository = reviewRepository;
        this.agentExecutor = agentExecutor;
        this.orchestrationTimer = Timer.builder("synthetiq.orchestration.duration")
                .description("End-to-end review orchestration time")
                .register(meterRegistry);
    }

    /**
     * Executes the full review pipeline for a given review request.
     * Called by the SQS message listener.
     *
     * <p>NOT @Transactional — uses scoped transactions via beginReview/completeReview
     * to avoid detached entity access from async agent threads.
     */
    public void executeReview(UUID reviewId) {
        Timer.Sample timerSample = Timer.start();

        try {
            // Transaction 1: Load entity, mark IN_PROGRESS, return snapshot
            ReviewSnapshot snapshot = beginReview(reviewId);

            // Step 1: Fetch changed files from GitHub (no transaction needed)
            List<CodeFile> files = gitHubClient.getPullRequestFiles(
                    snapshot.repoFullName(),
                    snapshot.prNumber(),
                    snapshot.installationId()
            );

            if (files.isEmpty()) {
                log.info("No files to analyze for review {}", reviewId);
                completeReview(reviewId, List.of());
                return;
            }

            log.info("Fetched {} files for review {}", files.size(), reviewId);

            // Step 2: Determine eligible agents
            List<CodeReviewAgent> eligibleAgents = agents.stream()
                    .filter(agent -> isAgentEnabled(agent.getType()))
                    .filter(agent -> agent.getMinimumTier().isWithinBudget(aiProperties.maxTier()))
                    .filter(agent -> agent.supports(files))
                    .toList();

            log.info("Eligible agents for review {}: {}", reviewId,
                    eligibleAgents.stream().map(a -> a.getType().name()).toList());

            // Step 3: Fan out to agents in parallel (using injected executor with MDC propagation)
            List<CompletableFuture<AgentResult>> futures = eligibleAgents.stream()
                    .map(agent -> CompletableFuture.supplyAsync(
                            () -> runAgent(agent, files, snapshot), agentExecutor)
                            .orTimeout(agentTimeout.toSeconds(), TimeUnit.SECONDS)
                            .exceptionally(ex -> AgentResult.failure(agent.getType(),
                                    "Agent timed out or failed: " + ex.getMessage())))
                    .toList();

            // Step 4: Await all results
            List<AgentResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            // Transaction 2: Persist results, post comment, mark complete
            completeReview(reviewId, results);

            long successCount = results.stream().filter(AgentResult::isSuccess).count();
            log.info("Review {} completed: {}/{} agents succeeded",
                    reviewId, successCount, results.size());

        } catch (Exception e) {
            log.error("Review {} failed: {}", reviewId, e.getMessage(), e);
            handleFailure(reviewId, e);
            throw e;  // Let SQS retry via visibility timeout
        } finally {
            timerSample.stop(orchestrationTimer);
        }
    }

    /**
     * Transaction 1: Load the review entity, mark it IN_PROGRESS, and return
     * a detached snapshot with the data agents need.
     */
    @Transactional
    public ReviewSnapshot beginReview(UUID reviewId) {
        ReviewRequest review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Review not found: " + reviewId));

        log.info("Starting orchestration for review {} on {}/pull/{}",
                reviewId, review.getRepositoryFullName(), review.getPullRequestNumber());

        review.markInProgress();
        reviewRepository.save(review);

        return new ReviewSnapshot(
                review.getId(),
                review.getRepositoryFullName(),
                review.getPullRequestNumber(),
                review.getHeadSha(),
                review.getInstallationId()
        );
    }

    /**
     * Transaction 2: Load the review fresh, persist agent results, post the
     * GitHub review comment, and mark COMPLETED.
     */
    @Transactional
    public void completeReview(UUID reviewId, List<AgentResult> results) {
        ReviewRequest review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Review not found: " + reviewId));

        results.forEach(review::addAgentResult);

        if (!results.isEmpty()) {
            String reviewBody = buildReviewComment(results, review);
            String reviewEvent = determineReviewEvent(results);

            gitHubClient.createReview(
                    review.getRepositoryFullName(),
                    review.getPullRequestNumber(),
                    review.getInstallationId(),
                    reviewBody,
                    reviewEvent
            );
        }

        review.markCompleted();
        reviewRepository.save(review);
    }

    /**
     * Handles failure within its own transaction scope.
     */
    @Transactional
    public void handleFailure(UUID reviewId, Exception e) {
        ReviewRequest review = reviewRepository.findById(reviewId).orElse(null);
        if (review == null) return;

        review.incrementRetry();
        if (review.getRetryCount() >= 3) {
            review.markFailed(e.getMessage());
        }
        reviewRepository.save(review);
    }

    // ── Internal ───────────────────────────────────────────────────

    /**
     * Immutable snapshot of review data needed by agents.
     * Avoids passing the managed entity to async threads.
     */
    record ReviewSnapshot(UUID reviewId, String repoFullName, int prNumber,
                          String headSha, long installationId) {}

    private AgentResult runAgent(CodeReviewAgent agent, List<CodeFile> files,
                                  ReviewSnapshot snapshot) {
        log.debug("Running {} agent for review {}", agent.getType(), snapshot.reviewId());
        try {
            AgentAnalysisResult analysis = agent.analyze(
                    files, snapshot.headSha(), snapshot.repoFullName());

            return AgentResult.success(
                    agent.getType(),
                    analysis.tierUsed(),
                    analysis.findingsJson(),
                    analysis.summary(),
                    analysis.inputTokens(),
                    analysis.outputTokens(),
                    analysis.duration()
            );
        } catch (Exception e) {
            log.warn("{} agent failed for review {}: {}", agent.getType(), snapshot.reviewId(), e.getMessage());
            return AgentResult.failure(agent.getType(), e.getMessage());
        }
    }

    private boolean isAgentEnabled(AgentType type) {
        return switch (type) {
            case SECURITY -> agentProperties.security().enabled();
            case PERFORMANCE -> agentProperties.performance().enabled();
            case ARCHITECTURE -> agentProperties.architecture().enabled();
            case REFACTORING -> agentProperties.refactoring().enabled();
            case REPORT -> true;  // Report agent is always enabled
        };
    }

    /**
     * Builds a formatted review comment from all agent results.
     */
    private String buildReviewComment(List<AgentResult> results, ReviewRequest review) {
        StringBuilder sb = new StringBuilder();
        sb.append("## \uD83D\uDD0D SynthetiQ Code Review\n\n");
        sb.append("**Repository**: %s | **PR**: #%d | **Commit**: `%s`\n\n"
                .formatted(review.getRepositoryFullName(),
                        review.getPullRequestNumber(),
                        review.getHeadSha().substring(0, 7)));

        for (AgentResult result : results) {
            String icon = result.isSuccess() ? "white_check_mark" : "x";
            sb.append("### %s %s Agent\n".formatted(icon, result.getAgentType()));

            if (result.isSuccess()) {
                sb.append(result.getSummary() != null ? result.getSummary() : "Analysis complete.");
            } else {
                sb.append("*Analysis failed: %s*".formatted(result.getErrorMessage()));
            }
            sb.append("\n\n");
        }

        sb.append("---\n");
        sb.append("*Powered by [SynthetiQ](https://github.com/your-username/synthetiq) — ");
        sb.append("Multi-Agent Code Review Platform*");

        return sb.toString();
    }

    /**
     * Determines whether to APPROVE, COMMENT, or REQUEST_CHANGES
     * based on the severity of findings.
     */
    private String determineReviewEvent(List<AgentResult> results) {
        boolean hasCritical = results.stream()
                .filter(AgentResult::isSuccess)
                .anyMatch(r -> r.getFindings().contains("\"severity\":\"CRITICAL\"") ||
                               r.getFindings().contains("\"severity\": \"CRITICAL\""));

        if (hasCritical) return "REQUEST_CHANGES";

        boolean hasHigh = results.stream()
                .filter(AgentResult::isSuccess)
                .anyMatch(r -> r.getFindings().contains("\"severity\":\"HIGH\"") ||
                               r.getFindings().contains("\"severity\": \"HIGH\""));

        return hasHigh ? "COMMENT" : "APPROVE";
    }
}
