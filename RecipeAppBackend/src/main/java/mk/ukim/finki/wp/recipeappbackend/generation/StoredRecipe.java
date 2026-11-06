package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.List;

/**
 * The {@code recipe} JSON of a {@code generated_recipes} row (spec section 4.1): the checked draft plus what
 * the code computed. {@code diets} and {@code allergens} come from the food rules, never from the AI;
 * {@code allergens} are food-rule keys.
 */
public record StoredRecipe(
        String title,
        String description,
        int servings,
        int prepMinutes,
        int cookMinutes,
        String cuisine,
        List<StoredIngredient> ingredients,
        List<String> steps,
        Diets diets,
        List<String> allergens) {

    /** {@code extra} is true when the user did not list the ingredient and it is not a pantry staple. */
    public record StoredIngredient(String name, String amount, boolean extra) {
    }

    /** True when no ingredient name breaks the diet. */
    public record Diets(boolean vegetarian, boolean vegan, boolean glutenFree, boolean dairyFree) {
    }
}
