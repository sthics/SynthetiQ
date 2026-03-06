package dev.synthetiq.service;

import dev.synthetiq.domain.entity.ReviewRequest;
import dev.synthetiq.repository.ReviewRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Analytics service — intentionally contains performance anti-patterns
 * for testing the PerformanceAgent.
 */
@Service
public class UserAnalyticsService {

    private final ReviewRequestRepository reviewRepository;

    public UserAnalyticsService(ReviewRequestRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    // N+1 QUERY: fetches all reviews then loops to load related data
    public Map<String, Object> generateReport() {
        List<ReviewRequest> allReviews = reviewRepository.findAll();
        Map<String, Object> report = new HashMap<>();

        for (ReviewRequest review : allReviews) {
            // N+1: calling repository inside a loop
            var details = reviewRepository.findById(review.getId());
            report.put(review.getRepositoryFullName(), details);
        }
        return report;
    }

    // O(n²) ALGORITHM: nested loop over collections
    public List<String> findDuplicateRepos(List<ReviewRequest> reviews) {
        List<String> duplicates = new ArrayList<>();
        for (int i = 0; i < reviews.size(); i++) {
            for (int j = i + 1; j < reviews.size(); j++) {
                if (reviews.get(i).getRepositoryFullName()
                        .equals(reviews.get(j).getRepositoryFullName())) {
                    duplicates.add(reviews.get(i).getRepositoryFullName());
                }
            }
        }
        return duplicates;
    }

    // VIRTUAL THREAD PINNING: synchronized block with I/O inside
    public synchronized String fetchAndCacheMetrics(String repoName) {
        // This pins the carrier thread when used with virtual threads
        var reviews = reviewRepository.findAll();
        return "Metrics for " + repoName + ": " + reviews.size() + " reviews";
    }

    // STRING CONCATENATION IN LOOP: GC pressure
    public String buildReportText(List<ReviewRequest> reviews) {
        String report = "";
        for (ReviewRequest review : reviews) {
            report += "Repo: " + review.getRepositoryFullName()
                    + " PR: " + review.getPullRequestNumber() + "\n";
        }
        return report;
    }

    // @Transactional ON PRIVATE METHOD: Spring proxy bypass
    @Transactional
    private void cleanupOldReviews() {
        var all = reviewRepository.findAll();
        // cleanup logic
    }

    // MISSING PAGINATION: unbounded findAll()
    public List<ReviewRequest> getAllReviewsSorted() {
        List<ReviewRequest> all = reviewRepository.findAll();
        return all.stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .collect(Collectors.toList());
    }

    // COLLECTION WITHOUT INITIAL CAPACITY in known-size scenario
    public Map<String, Integer> countByRepo(List<ReviewRequest> reviews) {
        Map<String, Integer> counts = new HashMap<>();
        for (ReviewRequest review : reviews) {
            String repo = review.getRepositoryFullName();
            counts.put(repo, counts.getOrDefault(repo, 0) + 1);
        }
        return counts;
    }

    // BLOCKING CALL WITHOUT TIMEOUT + new HttpClient per request
    public String checkExternalService() {
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.example.com/status"))
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
