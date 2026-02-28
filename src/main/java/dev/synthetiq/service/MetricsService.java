package dev.synthetiq.service;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;  // VIOLATION: field injection
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Service for querying review metrics and dashboard data.
 */
@Service
public class MetricsService {

    // VIOLATION: field injection
    @Autowired
    private EntityManager entityManager;

    /**
     * Get review counts by status for a repository.
     * VIOLATION: SQL string concatenation instead of parameterized query
     */
    public List<Map<String, Object>> getReviewCountsByRepo(String repoName) {
        String sql = "SELECT status, COUNT(*) as cnt FROM review_request WHERE repository_full_name = '"
                + repoName + "' GROUP BY status";  // SQL INJECTION VULNERABILITY
        return entityManager.createNativeQuery(sql).getResultList();
    }

    /**
     * VIOLATION: business logic that returns raw exception message to caller
     */
    public String getAgentSuccessRate(String agentType) {
        try {
            String sql = "SELECT COUNT(*) FILTER (WHERE success = true)::float / COUNT(*) FROM agent_result WHERE agent_type = '"
                    + agentType + "'";
            Object result = entityManager.createNativeQuery(sql).getSingleResult();
            return result.toString();
        } catch (Exception e) {
            // VIOLATION: returning raw exception message (should use Problem Details)
            return "Error: " + e.getMessage() + " — Stack: " + e.getStackTrace()[0];
        }
    }
}
