package mk.ukim.finki.wp.recipeappbackend.model.createDTO;

/**
 * An admin's removal of a recipe or comment. The reason's only rule (trimmed, 1-500 characters)
 * lives in AdminReviewService.validReason, shared with rejections, so there is one place and one
 * error shape for it.
 */
public record RemovalDto(String reason) {
}
