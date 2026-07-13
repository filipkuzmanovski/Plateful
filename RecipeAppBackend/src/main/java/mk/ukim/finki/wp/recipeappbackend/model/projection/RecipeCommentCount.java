package mk.ukim.finki.wp.recipeappbackend.model.projection;

import java.util.UUID;

/**
 * BUGFIX (review issue #6, N+1 aggregates): one row of the batched comment
 * count query in RecipeCommentRepository — comment counts for a whole page
 * of recipes in one query instead of one countByRecipeId() per recipe.
 */
public record RecipeCommentCount(
        UUID recipeId,
        Long commentCount
) {
}
