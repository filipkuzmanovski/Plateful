package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mk.ukim.finki.wp.recipeappbackend.food.Allergen;
import mk.ukim.finki.wp.recipeappbackend.food.FoodRules;
import mk.ukim.finki.wp.recipeappbackend.food.IngredientNames;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeSummaryDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Between a checked {@link RecipeDraft}, the {@link StoredRecipe} kept in {@code generated_recipes.recipe}
 * and the DTOs sent to the browser. Diets and allergens are computed here from the ingredient names.
 */
@Component
public class GeneratedRecipeMapper {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final FoodRules rules;

    @Autowired
    public GeneratedRecipeMapper(FoodRules rules) {
        this.rules = rules;
    }

    /**
     * @param extras normalised free-text names ({@code IngredientNames.normaliseText}) of the ingredients the
     *               user did not list, as {@link SafetyCheck} reports them
     */
    public StoredRecipe fromDraft(RecipeDraft d, Set<String> extras) {
        List<StoredRecipe.StoredIngredient> ingredients = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (RecipeDraft.Ingredient ingredient : d.ingredients()) {
            boolean extra = extras.contains(IngredientNames.normaliseText(ingredient.name()));
            ingredients.add(new StoredRecipe.StoredIngredient(ingredient.name(), ingredient.amount(), extra));
            IngredientNames.normalise(ingredient.name()).ifPresent(names::add);
        }
        StoredRecipe.Diets diets = new StoredRecipe.Diets(
                keeps(names, "vegetarian"), keeps(names, "vegan"),
                keeps(names, "gluten_free"), keeps(names, "dairy_free"));
        List<String> allergens = List.copyOf(rules.allergensIn(names));
        return new StoredRecipe(d.title(), d.description(), d.servings(), d.prepMinutes(), d.cookMinutes(),
                d.cuisine(), List.copyOf(ingredients), List.copyOf(d.steps()), diets, allergens);
    }

    /** The row's {@code recipe} document as a JSON map, for the JSONB column. */
    public Map<String, Object> toDocument(StoredRecipe recipe) {
        return MAPPER.convertValue(recipe, MAP);
    }

    public StoredRecipe read(GeneratedRecipe row) {
        return MAPPER.convertValue(row.getRecipe(), StoredRecipe.class);
    }

    public GeneratedRecipeDto toDto(GeneratedRecipe row) {
        StoredRecipe s = read(row);
        List<Allergen> allergens = new ArrayList<>();
        for (String key : s.allergens() == null ? List.<String>of() : s.allergens()) {
            allergens.add(new Allergen(key, labelOf(key)));
        }
        return new GeneratedRecipeDto(row.getId(), row.getKind(), row.getMealType(), row.getTitle(),
                s.description(), s.servings(), s.prepMinutes(), s.cookMinutes(), s.cuisine(),
                s.ingredients(), s.steps(), s.diets(), List.copyOf(allergens),
                row.getSavedAt() != null, row.getCreatedAt(), row.getProvider());
    }

    public GeneratedRecipeSummaryDto toSummary(GeneratedRecipe row) {
        StoredRecipe s = read(row);
        return new GeneratedRecipeSummaryDto(row.getId(), row.getKind(), row.getMealType(), row.getTitle(),
                s.description(), s.prepMinutes() + s.cookMinutes(), row.getSavedAt());
    }

    /** True when no ingredient name breaks the diet. */
    private boolean keeps(List<String> names, String diet) {
        return rules.dietViolations(names, Set.of(diet)).isEmpty();
    }

    /** An allergen key stored earlier may have left the rules file since; show the key rather than fail. */
    private String labelOf(String key) {
        try {
            return rules.label(key);
        } catch (IllegalArgumentException e) {
            return key;
        }
    }
}
