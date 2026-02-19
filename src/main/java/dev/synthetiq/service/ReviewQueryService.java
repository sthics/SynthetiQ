package dev.synthetiq.service;

import dev.synthetiq.domain.entity.AgentResult;
import dev.synthetiq.domain.entity.ReviewRequest;
import dev.synthetiq.dto.response.ReviewResponse;
import dev.synthetiq.repository.ReviewRequestRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

/** Read-side service with read-only transactions. */
@Service
@Transactional(readOnly = true)
public class ReviewQueryService {
    private final ReviewRequestRepository repository;
    public ReviewQueryService(ReviewRequestRepository repository) { this.repository = repository; }

    public Optional<ReviewResponse> findById(UUID id) {
        return repository.findById(id).map(this::toResponse);
    }

    public Page<ReviewResponse> findByRepository(String repo, int page, int size) {
        return repository.findByRepositoryFullNameOrderByCreatedAtDesc(repo, PageRequest.of(page, size))
                .map(this::toResponse);
    }

    private ReviewResponse toResponse(ReviewRequest e) {
        return new ReviewResponse(e.getId(), e.getRepositoryFullName(), e.getPullRequestNumber(),
                e.getHeadSha(), e.getStatus(), e.getAgentResults().size(),
                e.getAgentResults().stream().map(this::toSummary).toList(),
                e.getCreatedAt(), e.getCompletedAt());
    }

    private ReviewResponse.AgentResultSummary toSummary(AgentResult r) {
        return new ReviewResponse.AgentResultSummary(r.getAgentType().name(), r.isSuccess(),
                r.getSummary(), r.getAiTierUsed().name(), r.getDurationMs() != null ? r.getDurationMs() : 0);
    }
}
