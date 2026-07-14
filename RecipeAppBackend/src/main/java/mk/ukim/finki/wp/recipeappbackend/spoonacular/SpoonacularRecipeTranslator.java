package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import mk.ukim.finki.wp.recipeappbackend.model.InstructionStep;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure mapping from one Spoonacular complexSearch result to our entities.
 * No I/O, no Spring dependencies — everything testable against a JSON
 * fixture. All the shape-translation rules documented on InstructionStep
 * (flatten groups, renumber continuously, keep names only) live here.
 */
public class SpoonacularRecipeTranslator {

    public record TranslationResult(Recipe recipe, List<RecipeIngredient> ingredients) {
    }

    public TranslationResult translate(SpoonacularSearchResponse.Result result, String searchedCuisine) {
        Recipe recipe = Recipe.builder()
                .source("spoonacular")
                .user(null)
                .spoonacularId(result.id())
                .title(truncate(result.title(), 255))
                .image(result.image())
                .servings(result.servings())
                .readyInMinutes(result.readyInMinutes())
                .cookingMinutes(result.cookingMinutes())
                .preparationMinutes(result.preparationMinutes())
                .sourceName(truncate(result.sourceName(), 255))
                .sourceUrl(result.sourceUrl())
                .instructions(result.instructions())
                .instructionsSteps(flattenInstructions(result.analyzedInstructions()))
                .vegan(Boolean.TRUE.equals(result.vegan()))
                .vegetarian(Boolean.TRUE.equals(result.vegetarian()))
                .glutenFree(Boolean.TRUE.equals(result.glutenFree()))
                .dairyFree(Boolean.TRUE.equals(result.dairyFree()))
                .cuisines(mapCuisines(result.cuisines(), searchedCuisine))
                .calories(findNutrient(result.nutrition(), "Calories"))
                .proteinGrams(findNutrient(result.nutrition(), "Protein"))
                .fatGrams(findNutrient(result.nutrition(), "Fat"))
                .carbsGrams(findNutrient(result.nutrition(), "Carbohydrates"))
                .nutrition(compactNutrition(result.nutrition()))
                .build();

        return new TranslationResult(recipe, mapIngredients(result.extendedIngredients(), recipe));
    }

    /** Flatten all groups into one list, renumbered 1..N (raw numbers restart per group). */
    private List<InstructionStep> flattenInstructions(List<SpoonacularSearchResponse.AnalyzedInstruction> groups) {
        if (groups == null) {
            return List.of();
        }
        List<InstructionStep> steps = new ArrayList<>();
        int number = 1;
        for (SpoonacularSearchResponse.AnalyzedInstruction group : groups) {
            if (group.steps() == null) {
                continue;
            }
            for (SpoonacularSearchResponse.Step step : group.steps()) {
                if (step.step() == null || step.step().isBlank()) {
                    continue;
                }
                steps.add(new InstructionStep(number++, step.step(),
                        names(step.ingredients()), names(step.equipment())));
            }
        }
        return steps;
    }

    /** Raw items are objects ({id, name, image, ...}); keep the names only. */
    private List<String> names(List<SpoonacularSearchResponse.NamedItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .map(SpoonacularSearchResponse.NamedItem::name)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    /** Lowercase; fall back to the searched cuisine so every import stays filterable. */
    private List<String> mapCuisines(List<String> cuisines, String searchedCuisine) {
        if (cuisines == null || cuisines.isEmpty()) {
            return List.of(searchedCuisine.toLowerCase(Locale.ROOT));
        }
        return cuisines.stream().map(c -> c.toLowerCase(Locale.ROOT)).toList();
    }

    private java.math.BigDecimal findNutrient(SpoonacularSearchResponse.Nutrition nutrition, String name) {
        if (nutrition == null || nutrition.nutrients() == null) {
            return null;
        }
        return nutrition.nutrients().stream()
                .filter(n -> name.equalsIgnoreCase(n.name()))
                .map(SpoonacularSearchResponse.Nutrient::amount)
                .findFirst()
                .orElse(null);
    }

    /** Full nutrient list, reduced to {name, amount, unit}, for the display-only jsonb column. */
    private Map<String, Object> compactNutrition(SpoonacularSearchResponse.Nutrition nutrition) {
        if (nutrition == null || nutrition.nutrients() == null) {
            return null;
        }
        List<Map<String, Object>> nutrients = nutrition.nutrients().stream()
                .map(n -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("name", n.name());
                    m.put("amount", n.amount());
                    m.put("unit", n.unit());
                    return (Map<String, Object>) m;
                })
                .toList();
        return Map.of("nutrients", nutrients);
    }

    private List<RecipeIngredient> mapIngredients(List<SpoonacularSearchResponse.ExtendedIngredient> raw,
                                                  Recipe recipe) {
        if (raw == null) {
            return List.of();
        }
        List<RecipeIngredient> ingredients = new ArrayList<>();
        int sortOrder = 0;
        for (SpoonacularSearchResponse.ExtendedIngredient ingredient : raw) {
            String name = firstNonBlank(ingredient.nameClean(), ingredient.name());
            String text = firstNonBlank(ingredient.original(), ingredient.originalName(), ingredient.name());
            if (name == null || text == null) {
                continue; // nothing usable — drop rather than violate NOT NULL
            }
            ingredients.add(RecipeIngredient.builder()
                    .recipe(recipe)
                    .ingredientName(name)
                    .originalText(text)
                    .sortOrder(sortOrder++)
                    .build());
        }
        return ingredients;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
