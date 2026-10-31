package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.food.Allergen;
import mk.ukim.finki.wp.recipeappbackend.generation.StoredRecipe;

/**
 * One AI-generated recipe in full (spec section 7.1). {@code allergens} are the allergens found in the
 * ingredient names, computed by the code from the food rules and never taken from the AI.
 */
public record GeneratedRecipeDto(
        UUID id,
        String kind,
        String mealType,
        String title,
        String description,
        int servings,
        int prepMinutes,
        int cookMinutes,
        String cuisine,
        List<StoredRecipe.StoredIngredient> ingredients,
        List<String> steps,
        StoredRecipe.Diets diets,
        List<Allergen> allergens,
        boolean saved,
        OffsetDateTime createdAt,
        String provider
) {
}
