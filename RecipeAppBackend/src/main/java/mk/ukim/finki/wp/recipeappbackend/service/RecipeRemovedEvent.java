package mk.ukim.finki.wp.recipeappbackend.service;

import java.util.UUID;

/**
 * Published inside the transaction in which an admin removes a recipe. Everything the email needs is
 * captured here because the recipe is gone by the time the listener runs. authorEmail and authorId
 * are null for a recipe without an author.
 */
public record RecipeRemovedEvent(
        UUID recipeId,
        String title,
        String reason,
        String authorEmail,
        UUID authorId,
        UUID adminId
) {
}
