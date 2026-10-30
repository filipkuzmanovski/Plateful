package mk.ukim.finki.wp.recipeappbackend.model.projection;

import java.util.Set;

/**
 * What to look for in {@code reference_recipes}. The {@code wanted} and {@code dislikes} names may be raw;
 * {@code ReferenceDataRepository.findClosest} normalises them. The keys are allergen and diet keys. An empty
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
