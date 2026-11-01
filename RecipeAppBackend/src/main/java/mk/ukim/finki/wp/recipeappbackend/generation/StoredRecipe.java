package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.List;

/**
 * The {@code recipe} JSON of a {@code generated_recipes} row (spec section 4.1): the checked draft plus what
 * the code computed. {@code diets} and {@code allergens} come from the food rules, never from the AI;
 * {@code allergens} are food-rule keys. {@code post} is set only for {@code kind = 'post'} rows (a recipe
 * made from an Instagram post) and is null for every other kind and for documents stored before it existed.
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
        List<String> allergens,
        PostNote post) {

    /** A recipe without a post note, the shape every recipe had before posts. */
    public StoredRecipe(String title, String description, int servings, int prepMinutes, int cookMinutes,
                        String cuisine, List<StoredIngredient> ingredients, List<String> steps, Diets diets,
                        List<String> allergens) {
        this(title, description, servings, prepMinutes, cookMinutes, cuisine, ingredients, steps, diets,
                allergens, null);
    }

    /**
     * What the post showed: {@code origin} is {@code recipe} (the post gave a recipe) or {@code dish} (it
     * showed a dish only); {@code swaps} are the ingredients replaced for the user's allergies and diet.
     */
    public record PostNote(String origin, List<Swap> swaps) {
    }

    /** One ingredient of the post replaced by another. */
    public record Swap(String from, String to) {
    }

    /** {@code extra} is true when the user did not list the ingredient and it is not a pantry staple. */
    public record StoredIngredient(String name, String amount, boolean extra) {
    }

    /** True when no ingredient name breaks the diet. */
    public record Diets(boolean vegetarian, boolean vegan, boolean glutenFree, boolean dairyFree) {
    }
}
