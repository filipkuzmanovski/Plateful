package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import java.util.List;

/**
 * A user's food preferences, used as both the PUT request body and the response. Allergens and diets
 * are keys from food-rules.csv, ingredients are normalised names, cuisines are keys from cuisines.csv.
 * A null list in a request is treated as empty.
 */
public record PreferencesDto(
        List<String> allergens,
        List<String> diets,
        List<String> dislikedIngredients,
        List<String> likedIngredients,
        List<String> likedCuisines
) {
}
