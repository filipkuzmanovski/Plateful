package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;


import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeRating;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * No recipe field, on purpose — you only ever look at a recipe's ratings
 * while already viewing that recipe, so re-sending the entire Recipe payload
 * inside every rating row would just be duplicated data for no reason. If
 * you later want a "my ratings across all recipes" page, that's a different,
 * lighter shape (recipe id + title, not the full Recipe) — say so if you
 * want that added.
 */
public record RecipeRatingDisplayDto(
        UUID id,
        UserDisplayDto user,
        Integer rating,
        OffsetDateTime createdAt
) {
    public static RecipeRatingDisplayDto fromEntity(RecipeRating recipeRating) {
        return new RecipeRatingDisplayDto(
                recipeRating.getId(),
                UserDisplayDto.fromEntity(recipeRating.getUser()),
                recipeRating.getRating(),
                recipeRating.getCreatedAt()
        );
    }
}
