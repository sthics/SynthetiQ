package dev.synthetiq.repository;

import dev.synthetiq.domain.entity.ReviewRequest;
import dev.synthetiq.domain.enums.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewRequestRepository extends JpaRepository<ReviewRequest, UUID> {
    Optional<ReviewRequest> findByIdempotencyKey(String idempotencyKey);
    Page<ReviewRequest> findByRepositoryFullNameOrderByCreatedAtDesc(String repo, Pageable pageable);
    long countByStatus(ReviewStatus status);
}
