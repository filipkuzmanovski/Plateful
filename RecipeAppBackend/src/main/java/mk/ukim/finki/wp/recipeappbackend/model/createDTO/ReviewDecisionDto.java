package mk.ukim.finki.wp.recipeappbackend.model.createDTO;

import jakarta.validation.constraints.NotNull;

/**
 * An admin's decision. revision is the one the review page was rendered with (a stale one is refused);
 * reason is required for a rejection and ignored for an approval. Its only rule (trimmed, 1-500
 * characters) lives in AdminReviewService, so there is one place and one error shape for it.
 */
public record ReviewDecisionDto(
        @NotNull Integer revision,
        String reason
) {
}
