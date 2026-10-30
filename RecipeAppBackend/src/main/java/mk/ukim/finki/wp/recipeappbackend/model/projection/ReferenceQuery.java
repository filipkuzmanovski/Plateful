package mk.ukim.finki.wp.recipeappbackend.model.projection;

import java.util.Set;

/**
 * What to look for in {@code reference_recipes}. All names are already normalised (letters and single
 * spaces only, see {@code IngredientNames}); the keys are validated allergen and diet keys. An empty
 * {@code allergens}, {@code diets} or {@code dislikes} set means no filter for that clause, and a
 * null {@code mealType} means any meal.
 */
public record ReferenceQuery(
        Set<String> wanted,
        String mealType,
        Set<String> allergens,
        Set<String> diets,
        Set<String> dislikes,
        int limit) {
}
