package mk.ukim.finki.wp.recipeappbackend.model.createDTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import mk.ukim.finki.wp.recipeappbackend.model.InstructionStep;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;


import java.util.ArrayList;
import java.util.List;

/**
 * What a client sends to create a user-submitted recipe.
 * <p>
 * Deliberately excluded: id, user_id (comes from the JWT, not the body),
 * source (always "user" through this endpoint — Recipe's own @Builder.Default
 * handles that), spoonacular_id, created_at/updated_at (server-controlled),
 * and the nutrition fields — those are computed values, not something a
 * person hand-types when submitting their own recipe.
 * <p>
 * Two conversion methods rather than one: Recipe has no back-reference
 * collection to its ingredients (relationships are one-directional here), so
 * toEntity() builds just the Recipe, and toIngredientEntities(Recipe) builds
 * the ingredient rows afterward, once the saved Recipe actually has an id
 * for them to reference.
 * <p>
 * BUGFIXES (review issues #1 and #3):
 * - instructionsSteps was List&lt;String&gt; while the entity/DB store structured
 *   step objects — now List&lt;InstructionStep&gt;, same shape end to end.
 * - Every previously UNBOUNDED string field (image, sourceUrl, instructions,
 *   cuisines elements) now has a @Size cap. Without caps, a client could POST
 *   a multi-megabyte "instructions" string straight into a TEXT column.
 * - @NotNull removed from ingredients: @NotEmpty already rejects null AND
 *   empty, so @NotNull was redundant.
 * - cuisines is capped at 10 entries of max 50 chars. NOTE: validating each
 *   value against the fixed cuisine list is still the service layer's job,
 *   as originally planned — these caps just stop garbage/abuse at the door.
 */
public record RecipeCreateDto(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 2_048) String image,
        @PositiveOrZero Integer servings,
        @PositiveOrZero Integer readyInMinutes,
        @PositiveOrZero Integer cookingMinutes,
        @PositiveOrZero Integer preparationMinutes,
        @Size(max = 255) String sourceName,
        @Size(max = 2_048) String sourceUrl,
        @Size(max = 50_000) String instructions,
        @Size(max = 100) List<@Valid InstructionStep> instructionsSteps,
        boolean dairyFree,
        boolean glutenFree,
        boolean vegan,
        boolean vegetarian,
        @Size(max = 10) List<@NotBlank @Size(max = 50) String> cuisines,
        @NotEmpty @Size(max = 100) @Valid List<RecipeIngredientCreateDto> ingredients
) {
    public Recipe toEntity(User owner) {
        return Recipe.builder()
                .user(owner)
                .title(title)
                .image(image)
                .servings(servings)
                .readyInMinutes(readyInMinutes)
                .cookingMinutes(cookingMinutes)
                .preparationMinutes(preparationMinutes)
                .sourceName(sourceName)
                .sourceUrl(sourceUrl)
                .instructions(instructions)
                .instructionsSteps(instructionsSteps != null ? instructionsSteps : List.of())
                .dairyFree(dairyFree)
                .glutenFree(glutenFree)
                .vegan(vegan)
                .vegetarian(vegetarian)
                .cuisines(cuisines != null ? cuisines : List.of())
                .build();
    }

    public List<RecipeIngredient> toIngredientEntities(Recipe recipe) {
        List<RecipeIngredient> result = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            result.add(ingredients.get(i).toEntity(recipe, i));
        }
        return result;
    }
}
