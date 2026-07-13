package mk.ukim.finki.wp.recipeappbackend.model.createDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;


/**
 * One ingredient line as submitted by the client — freeform on purpose.
 * sortOrder isn't a field here; it's passed into toEntity() by whoever is
 * looping over the parent list, based on each item's position in it.
 * <p>
 * BUGFIX (review issue #3): both strings were unbounded — a client could
 * stuff megabytes into a single "ingredient". @Size caps added.
 */
public record RecipeIngredientCreateDto(
        @NotBlank @Size(max = 255) String ingredientName,
        @NotBlank @Size(max = 500) String originalText
) {
    public RecipeIngredient toEntity(Recipe recipe, int sortOrder) {
        return RecipeIngredient.builder()
                .recipe(recipe)
                .ingredientName(ingredientName)
                .originalText(originalText)
                .sortOrder(sortOrder)
                .build();
    }
}
