package dev.synthetiq.controller;

import dev.synthetiq.dto.response.ReviewResponse;
import dev.synthetiq.service.ReviewQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/reviews")
public class ReviewController {
    private final ReviewQueryService queryService;
    public ReviewController(ReviewQueryService queryService) { this.queryService = queryService; }

    @GetMapping("/{id}")
    public ResponseEntity<ReviewResponse> getReview(@PathVariable UUID id) {
        return queryService.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<?> getReviewsByRepo(@RequestParam String repository,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(queryService.findByRepository(repository, page, size));
    }
}
