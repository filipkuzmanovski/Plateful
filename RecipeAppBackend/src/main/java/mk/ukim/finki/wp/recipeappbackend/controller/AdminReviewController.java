package mk.ukim.finki.wp.recipeappbackend.controller;

import jakarta.validation.Valid;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.ReviewDecisionDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.security.AdminGuard;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.AdminReviewService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** The admin review API. Every route asks {@link AdminGuard} before it does anything else. */
@RestController
@RequestMapping("/api/admin")
public class AdminReviewController {

    private final AdminGuard adminGuard;
    private final AdminReviewService reviewService;

    public AdminReviewController(AdminGuard adminGuard, AdminReviewService reviewService) {
        this.adminGuard = adminGuard;
        this.reviewService = reviewService;
    }

    /** Size is capped by spring.data.web.pageable.max-page-size; the order is fixed (oldest first). */
    @GetMapping("/recipes/pending")
    public Page<RecipeDisplayDto> pending(@CurrentUserId UUID adminId, Pageable pageable) {
        adminGuard.requireAdmin(adminId);
        return reviewService.pending(pageable);
    }

    @GetMapping("/recipes/pending/count")
    public Map<String, Long> pendingCount(@CurrentUserId UUID adminId) {
        adminGuard.requireAdmin(adminId);
        return Map.of("count", reviewService.pendingCount());
    }

    @GetMapping("/recipes/{id}")
    public RecipeDisplayDto get(@CurrentUserId UUID adminId, @PathVariable UUID id) {
        adminGuard.requireAdmin(adminId);
        return reviewService.get(id);
    }

    @PostMapping("/recipes/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@CurrentUserId UUID adminId, @PathVariable UUID id,
                        @Valid @RequestBody ReviewDecisionDto body) {
        adminGuard.requireAdmin(adminId);
        reviewService.approve(adminId, id, body.revision());
    }

    @PostMapping("/recipes/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@CurrentUserId UUID adminId, @PathVariable UUID id,
                       @Valid @RequestBody ReviewDecisionDto body) {
        adminGuard.requireAdmin(adminId);
        reviewService.reject(adminId, id, body.revision(), body.reason());
    }
}
