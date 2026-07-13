package mk.ukim.finki.wp.recipeappbackend.model.projection;

import java.util.UUID;

/**
 * BUGFIX (review issue #6, N+1 aggregates): one row of the batched
 * "AVG + COUNT per recipe" query in RecipeRatingRepository. Lets a browse
 * page of 20 recipes fetch all rating stats in ONE query instead of 40
 * (one AVG + one COUNT per recipe). Filled in by a JPQL constructor
 * expression — SELECT new ...RecipeRatingSummary(...).
 */
public record RecipeRatingSummary(
        UUID recipeId,
        Double averageRating,
        Long ratingCount
) {
}
