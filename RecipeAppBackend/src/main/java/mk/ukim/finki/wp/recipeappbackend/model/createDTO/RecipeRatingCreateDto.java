package mk.ukim.finki.wp.recipeappbackend.model.createDTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeRating;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;


/**
 * recipe and user come from the URL path / JWT, never the body. Also doubles
 * as the "update my rating" payload — re-rating is an upsert on
 * (recipe_id, user_id) handled in the service layer, not a new DTO shape.
 */
public record RecipeRatingCreateDto(
        @NotNull @Min(1) @Max(5) Integer rating
) {
    public RecipeRating toEntity(Recipe recipe, User user) {
        return RecipeRating.builder()
                .recipe(recipe)
                .user(user)
                .rating(rating)
                .build();
    }
}
