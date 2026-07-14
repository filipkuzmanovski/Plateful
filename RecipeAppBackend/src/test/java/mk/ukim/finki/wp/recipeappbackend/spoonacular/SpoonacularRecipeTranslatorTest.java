package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import mk.ukim.finki.wp.recipeappbackend.model.InstructionStep;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpoonacularRecipeTranslatorTest {

    private static SpoonacularSearchResponse.Result full;      // recipe 715538
    private static SpoonacularSearchResponse.Result sparse;    // recipe 999001

    private final SpoonacularRecipeTranslator translator = new SpoonacularRecipeTranslator();

    @BeforeAll
    static void loadFixture() throws Exception {
        try (InputStream in = SpoonacularRecipeTranslatorTest.class
                .getResourceAsStream("/spoonacular/complex-search-response.json")) {
            SpoonacularSearchResponse response = JsonMapper.builder().build()
                    .readValue(in, SpoonacularSearchResponse.class);
            full = response.results().get(0);
            sparse = response.results().get(1);
        }
    }

    @Test
    void mapsCoreFieldsAndProvenance() {
        Recipe r = translator.translate(full, "italian").recipe();

        assertThat(r.getSource()).isEqualTo("spoonacular");
        assertThat(r.getUser()).isNull();
        assertThat(r.getSpoonacularId()).isEqualTo(715538L);
        assertThat(r.getTitle()).isEqualTo("Bruschetta Style Pork & Pasta");
        assertThat(r.getServings()).isEqualTo(5);
        assertThat(r.getReadyInMinutes()).isEqualTo(35);
        assertThat(r.getSourceName()).isEqualTo("Pink When");
        assertThat(r.getInstructions()).contains("Cook the pasta");
    }

    @Test
    void flattensInstructionGroupsWithContinuousNumbering() {
        List<InstructionStep> steps = translator.translate(full, "italian").recipe().getInstructionsSteps();

        assertThat(steps).hasSize(4);
        assertThat(steps).extracting(InstructionStep::number).containsExactly(1, 2, 3, 4);
        // group 2's first step (raw number 1) became step 3
        assertThat(steps.get(2).step()).contains("Dice the tomatoes");
        // names only — objects reduced to their name field
        assertThat(steps.get(0).ingredients()).containsExactly("pasta");
        assertThat(steps.get(1).equipment()).containsExactly("frying pan");
        assertThat(steps.get(3).ingredients()).isEmpty();
    }

    @Test
    void extractsMacrosAndKeepsFullNutrientListInJsonbMap() {
        Recipe r = translator.translate(full, "italian").recipe();

        assertThat(r.getCalories()).isEqualByComparingTo(new BigDecimal("543.36"));
        assertThat(r.getProteinGrams()).isEqualByComparingTo(new BigDecimal("21.1"));
        assertThat(r.getFatGrams()).isEqualByComparingTo(new BigDecimal("16.2"));
        assertThat(r.getCarbsGrams()).isEqualByComparingTo(new BigDecimal("74.79"));
        assertThat(r.getNutrition()).containsKey("nutrients");
        assertThat((List<?>) r.getNutrition().get("nutrients")).hasSize(5); // Sodium kept too
    }

    @Test
    void lowercasesCuisines() {
        Recipe r = translator.translate(full, "italian").recipe();
        assertThat(r.getCuisines()).containsExactly("mediterranean", "italian");
    }

    @Test
    void emptyCuisinesFallBackToSearchedCuisine() {
        Recipe r = translator.translate(sparse, "Thai").recipe();
        assertThat(r.getCuisines()).containsExactly("thai");
    }

    @Test
    void mapsIngredientsWithFallbacksAndDropsEmptyOnes() {
        List<RecipeIngredient> ingredients = translator.translate(full, "italian").ingredients();

        assertThat(ingredients).hasSize(2);
        assertThat(ingredients.get(0).getIngredientName()).isEqualTo("penne");            // nameClean wins
        assertThat(ingredients.get(0).getOriginalText()).isEqualTo("8 oz penne pasta, cooked");
        assertThat(ingredients.get(0).getSortOrder()).isEqualTo(0);
        assertThat(ingredients.get(1).getIngredientName()).isEqualTo("pork chops");       // nameClean null -> name
        assertThat(ingredients.get(1).getSortOrder()).isEqualTo(1);

        List<RecipeIngredient> sparseIngredients = translator.translate(sparse, "thai").ingredients();
        assertThat(sparseIngredients).hasSize(1); // fully-empty ingredient dropped
        assertThat(sparseIngredients.get(0).getIngredientName()).isEqualTo("cilantro");
        assertThat(sparseIngredients.get(0).getOriginalText()).isEqualTo("cilantro");     // text falls back to name
    }

    @Test
    void toleratesMissingOptionalSections() {
        Recipe r = translator.translate(sparse, "thai").recipe();

        assertThat(r.getInstructionsSteps()).isEmpty();  // no analyzedInstructions
        assertThat(r.getNutrition()).isNull();           // no nutrition block
        assertThat(r.getCalories()).isNull();
        assertThat(r.isVegan()).isTrue();
        assertThat(r.isDairyFree()).isFalse();           // absent flag -> false
        assertThat(r.getCookingMinutes()).isNull();
    }

    @Test
    void truncatesOverlongTitleTo255() {
        Recipe r = translator.translate(sparse, "thai").recipe();
        assertThat(r.getTitle()).hasSize(255);
        assertThat(r.getTitle()).startsWith("An Extremely Long Recipe Title");
    }

    @Test
    void ingredientsReferenceTheTranslatedRecipe() {
        SpoonacularRecipeTranslator.TranslationResult result = translator.translate(full, "italian");
        assertThat(result.ingredients()).allSatisfy(i -> assertThat(i.getRecipe()).isSameAs(result.recipe()));
    }
}
