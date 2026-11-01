package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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

    /** The diets a stored recipe is labelled with. */
    private static final Set<String> DIETS = Set.of("vegetarian", "vegan", "gluten_free", "dairy_free");

    private final FoodRules rules;

    @Autowired
    public GeneratedRecipeMapper(FoodRules rules) {
        this.rules = rules;
    }

    /**
     * Diets and allergens are computed per ingredient name in both normalised forms, as {@link SafetyCheck}
     * checks them: the ingredient form ({@code IngredientNames.normalise}, which glues punctuation and
     * singularises only the last word) and the free-text form ({@code FoodRules.allergensInText}), so
     * "Eggs, beaten" counts as eggs and "walnuts/pecans" as tree nuts.
     *
     * @param extras normalised free-text names ({@code IngredientNames.normaliseText}) of the ingredients the
     *               user did not list, as {@link SafetyCheck} reports them
     */
    public StoredRecipe fromDraft(RecipeDraft d, Set<String> extras) {
        List<StoredRecipe.StoredIngredient> ingredients = new ArrayList<>();
        Set<String> allergens = new LinkedHashSet<>();
        Set<String> broken = new LinkedHashSet<>();
        for (RecipeDraft.Ingredient ingredient : d.ingredients()) {
            String raw = ingredient.name();
            boolean extra = extras.contains(IngredientNames.normaliseText(raw));
            ingredients.add(new StoredRecipe.StoredIngredient(raw, ingredient.amount(), extra));
            List<String> name = IngredientNames.normalise(raw).map(List::of).orElse(List.of());
            allergens.addAll(rules.allergensIn(name));
            allergens.addAll(rules.allergensInText(raw));
            broken.addAll(rules.dietViolations(name, DIETS));
            broken.addAll(rules.dietViolationsInText(raw, DIETS));
        }
        StoredRecipe.Diets diets = new StoredRecipe.Diets(
                !broken.contains("vegetarian"), !broken.contains("vegan"),
                !broken.contains("gluten_free"), !broken.contains("dairy_free"));
        // In the order of the rules file, as before, not in ingredient order.
        List<String> ordered = rules.allergenKeys().stream().filter(allergens::contains).toList();
        return new StoredRecipe(d.title(), d.description(), d.servings(), d.prepMinutes(), d.cookMinutes(),
                d.cuisine(), List.copyOf(ingredients), List.copyOf(d.steps()), diets, ordered);
    }

    /**
     * The stored recipe as a draft again, so a stored recipe can be re-checked against the user's current
     * preferences with {@link SafetyCheck}. Missing lists become empty and missing texts blank, so an odd
     * stored document is checked rather than failing.
     */
    public RecipeDraft toDraft(StoredRecipe s) {
        List<RecipeDraft.Ingredient> ingredients = new ArrayList<>();
        for (StoredRecipe.StoredIngredient ingredient : s.ingredients() == null
                ? List.<StoredRecipe.StoredIngredient>of() : s.ingredients()) {
            if (ingredient != null) {
                ingredients.add(new RecipeDraft.Ingredient(text(ingredient.name()), text(ingredient.amount())));
            }
        }
        List<String> steps = new ArrayList<>();
        for (String step : s.steps() == null ? List.<String>of() : s.steps()) {
            steps.add(text(step));
        }
        return new RecipeDraft(text(s.title()), text(s.description()), s.servings(), s.prepMinutes(),
                s.cookMinutes(), s.cuisine(), List.copyOf(ingredients), List.copyOf(steps));
    }

    private static String text(String value) {
        return value == null ? "" : value;
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

    /** An allergen key stored earlier may have left the rules file since; show the key rather than fail. */
    private String labelOf(String key) {
        try {
            return rules.label(key);
        } catch (IllegalArgumentException e) {
            return key;
        }
    }
}
