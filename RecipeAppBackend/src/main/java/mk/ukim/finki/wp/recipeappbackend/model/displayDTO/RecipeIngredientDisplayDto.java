package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;

/**
 * No id, no recipe reference — ingredients are always shown nested inside a
 * RecipeDisplayDto's ingredients list, never fetched on their own, so there's
 * nothing here beyond the two things actually shown to the user. If you
 * later want a "check off ingredients while cooking" feature client-side,
 * an id would be worth adding then — easy to do, not needed yet.
 */
public record RecipeIngredientDisplayDto(
        String ingredientName,
        String originalText
) {
    public static RecipeIngredientDisplayDto fromEntity(RecipeIngredient ingredient) {
        return new RecipeIngredientDisplayDto(ingredient.getIngredientName(), ingredient.getOriginalText());
    }
}