package mk.ukim.finki.wp.recipeappbackend.service;

import java.util.UUID;

/**
 * Published inside the review transaction when an admin approves or rejects a recipe. authorEmail
 * and authorId are null for a recipe without an author; reason is null for an approval.
 */
public record RecipeReviewedEvent(
        UUID recipeId,
        boolean approved,
        String title,
        String reason,
        String authorEmail,
        UUID authorId,
        UUID adminId
) {
}
