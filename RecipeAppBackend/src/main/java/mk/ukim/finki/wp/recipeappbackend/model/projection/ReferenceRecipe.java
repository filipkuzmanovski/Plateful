package mk.ukim.finki.wp.recipeappbackend.model.projection;

import java.util.List;

/** A dataset recipe from {@code reference_recipes}; {@code overlap} is how many wanted names it contains. */
public record ReferenceRecipe(
        long id,
        String title,
        List<String> ingredientLines,
        List<String> ingredientNames,
        List<String> directions,
        List<String> mealTypes,
        int overlap) {
}
