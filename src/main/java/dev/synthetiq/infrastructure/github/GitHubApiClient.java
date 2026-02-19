package dev.synthetiq.infrastructure.github;

import dev.synthetiq.domain.valueobject.CodeFile;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;

/**
 * GitHub REST API client with circuit breaker and rate limiting.
 * Uses WebClient (non-blocking) with .block() — safe on virtual threads.
 */
@Component
public class GitHubApiClient {
    private static final Logger log = LoggerFactory.getLogger(GitHubApiClient.class);
    private final WebClient webClient;
    private final GitHubTokenProvider tokenProvider;

    public GitHubApiClient(WebClient.Builder builder, GitHubTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
        this.webClient = builder.baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github.v3+json").build();
    }

    @CircuitBreaker(name = "github-api", fallbackMethod = "fallbackGetPrFiles")
    @RateLimiter(name = "github-api")
    public List<CodeFile> getPullRequestFiles(String repo, int pr, long installationId) {
        String token = tokenProvider.getInstallationToken(installationId);
        List<Map<String, Object>> files = webClient.get()
                .uri("/repos/{repo}/pulls/{pr}/files", repo, pr)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block();
        if (files == null) return List.of();
        return files.stream().map(f -> new CodeFile(
                (String) f.get("filename"),
                CodeFile.detectLanguage((String) f.get("filename")),
                (String) f.get("patch"),
                (int) f.getOrDefault("additions", 0),
                (int) f.getOrDefault("deletions", 0),
                null
        )).toList();
    }

    @CircuitBreaker(name = "github-api") @RateLimiter(name = "github-api")
    public void createReview(String repo, int pr, long installationId, String body, String event) {
        String token = tokenProvider.getInstallationToken(installationId);
        webClient.post().uri("/repos/{repo}/pulls/{pr}/reviews", repo, pr)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(Map.of("body", body, "event", event))
                .retrieve().toBodilessEntity().block();
        log.info("Posted review on {}/pull/{}", repo, pr);
    }

    @SuppressWarnings("unused")
    private List<CodeFile> fallbackGetPrFiles(String r, int p, long i, Throwable t) {
        log.error("GitHub circuit open: {}", t.getMessage());
        return List.of();
    }
}
