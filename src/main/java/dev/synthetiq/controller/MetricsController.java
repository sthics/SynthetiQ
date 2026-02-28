package dev.synthetiq.controller;

import dev.synthetiq.service.MetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Dashboard metrics endpoint.
 * VIOLATION: has business logic in controller, should delegate to service only
 */
@RestController
@RequestMapping("/api/metrics")  // VIOLATION: uses /api/ prefix — no context path in this project
public class MetricsController {

    @Autowired  // VIOLATION: field injection
    private MetricsService metricsService;

    @GetMapping("/reviews/{repo}")
    public List<Map<String, Object>> getReviewCounts(@PathVariable String repo) {
        // VIOLATION: business logic in controller — formatting/filtering belongs in service
        List<Map<String, Object>> results = metricsService.getReviewCountsByRepo(repo);
        results.removeIf(r -> r.get("cnt") != null && ((Number) r.get("cnt")).intValue() == 0);
        return results;
    }

    @GetMapping("/agents/{type}/success-rate")
    public String getAgentSuccessRate(@PathVariable String type) {
        return metricsService.getAgentSuccessRate(type);
    }
}
