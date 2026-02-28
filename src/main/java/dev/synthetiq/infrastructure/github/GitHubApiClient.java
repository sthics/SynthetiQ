package dev.synthetiq.infrastructure.github;

import dev.synthetiq.domain.valueobject.CodeFile;
import dev.synthetiq.domain.valueobject.ProjectGuide;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(30))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000);
        this.webClient = builder.baseUrl("https://api.github.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github.v3+json").build();
    }

    private static final int FILES_PER_PAGE = 100;
    private static final int MAX_PAGES = 10;

    @CircuitBreaker(name = "github-api", fallbackMethod = "fallbackGetPrFiles")
    @RateLimiter(name = "github-api")
    public List<CodeFile> getPullRequestFiles(String repo, int pr, long installationId) {
        String token = tokenProvider.getInstallationToken(installationId);
        List<CodeFile> allFiles = new ArrayList<>();

        for (int page = 1; page <= MAX_PAGES; page++) {
            List<Map<String, Object>> files = webClient.get()
                    .uri("/repos/" + repo + "/pulls/" + pr + "/files?per_page=" + FILES_PER_PAGE + "&page=" + page)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                    .block();

            if (files == null || files.isEmpty()) break;

            files.stream().map(f -> new CodeFile(
                    (String) f.get("filename"),
                    CodeFile.detectLanguage((String) f.get("filename")),
                    (String) f.get("patch"),
                    (int) f.getOrDefault("additions", 0),
                    (int) f.getOrDefault("deletions", 0),
                    null
            )).forEach(allFiles::add);

            if (files.size() < FILES_PER_PAGE) break;
        }

        if (allFiles.size() >= FILES_PER_PAGE * MAX_PAGES) {
            log.warn("PR {}/pull/{} has {}+ files, review may be incomplete", repo, pr, allFiles.size());
        }
        return allFiles;
    }

    @CircuitBreaker(name = "github-api") @RateLimiter(name = "github-api")
    public void createReview(String repo, int pr, long installationId, String body, String event) {
        String token = tokenProvider.getInstallationToken(installationId);
        webClient.post().uri("/repos/" + repo + "/pulls/" + pr + "/reviews")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(Map.of("body", body, "event", event))
                .retrieve().toBodilessEntity().block();
        log.info("Review posted on {}/pull/{}", repo, pr);
    }

    /**
     * Fetches the SYNTHETIQ.md project guide from the repo root.
     * Returns Optional.empty() if the file does not exist or on any error.
     * Never fails the review pipeline — guide is supplementary context.
     */
    public Optional<ProjectGuide> getProjectGuide(String repo, long installationId) {
        try {
            String token = tokenProvider.getInstallationToken(installationId);
            String raw = webClient.get()
                    .uri("/repos/" + repo + "/contents/SYNTHETIQ.md")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.raw+json")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return ProjectGuide.of(raw);
        } catch (Exception e) {
            log.warn("Could not fetch SYNTHETIQ.md from {}: {}", repo, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unused")
    private List<CodeFile> fallbackGetPrFiles(String r, int p, long i, Throwable t) {
        log.error("GitHub circuit open: {}", t.getMessage());
        return List.of();
    }
}
