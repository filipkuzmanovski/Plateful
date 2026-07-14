package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * The slice of Spoonacular's complexSearch response we consume (with
 * addRecipeInformation, addRecipeNutrition and fillIngredients enabled).
 * Boxed Booleans on purpose: absent flags must map to false in the
 * translator, never depend on mapper leniency for primitives.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpoonacularSearchResponse(List<Result> results) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            long id,
            String title,
            String image,
            Integer servings,
            Integer readyInMinutes,
            Integer cookingMinutes,
            Integer preparationMinutes,
            String sourceName,
            String sourceUrl,
            String instructions,
            List<AnalyzedInstruction> analyzedInstructions,
            Boolean vegan,
            Boolean vegetarian,
            Boolean glutenFree,
            Boolean dairyFree,
            List<String> cuisines,
            Nutrition nutrition,
            List<ExtendedIngredient> extendedIngredients
    ) {
    }

    /** One instruction GROUP (e.g. "For the sauce") — steps renumber per group in the raw API. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnalyzedInstruction(String name, List<Step> steps) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(Integer number, String step, List<NamedItem> ingredients, List<NamedItem> equipment) {
    }

    /** Raw API items are objects ({id, name, image, ...}); we keep names only. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NamedItem(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Nutrition(List<Nutrient> nutrients) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Nutrient(String name, BigDecimal amount, String unit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtendedIngredient(String name, String nameClean, String original, String originalName) {
    }
}
