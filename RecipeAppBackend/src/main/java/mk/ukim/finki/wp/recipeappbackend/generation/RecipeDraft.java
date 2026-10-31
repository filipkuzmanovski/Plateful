package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.List;

/**
 * A recipe as the AI returned it, after {@link DraftParser} has checked its structure. {@code cuisine} is a
 * {@code CuisineCatalog} key or null. Ingredient names are the stripped text the AI wrote; the checks
 * normalise them.
 */
public record RecipeDraft(
        String title,
        String description,
        int servings,
        int prepMinutes,
        int cookMinutes,
        String cuisine,
        List<Ingredient> ingredients,
        List<String> steps) {

    public record Ingredient(String name, String amount) {
    }
}
