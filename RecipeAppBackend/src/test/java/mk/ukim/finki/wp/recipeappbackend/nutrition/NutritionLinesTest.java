package mk.ukim.finki.wp.recipeappbackend.nutrition;

import static mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionLines.forGenerated;
import static mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionLines.forRecipe;
import static mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionLines.hash;
import static mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionLines.numbered;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import mk.ukim.finki.wp.recipeappbackend.generation.StoredRecipe;
import mk.ukim.finki.wp.recipeappbackend.generation.StoredRecipe.StoredIngredient;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;
import org.junit.jupiter.api.Test;

class NutritionLinesTest {

    private static RecipeIngredient ingredient(String name, String text) {
        return RecipeIngredient.builder().ingredientName(name).originalText(text).build();
    }

    private static StoredRecipe stored(StoredIngredient... ingredients) {
        return new StoredRecipe("t", "d", 1, 0, 0, "c", List.of(ingredients), List.of(), null, List.of());
    }

    private static StoredRecipe storedWithNullIngredients() {
        return new StoredRecipe("t", "d", 1, 0, 0, "c", null, List.of(), null, List.of());
    }

    @Test
    void recipeLinesKeepNameAndText() {
        assertThat(forRecipe(List.of(ingredient("flour", "2 cups")))).containsExactly("flour | 2 cups");
    }

    @Test
    void blankTextMeansJustTheName() {
        assertThat(forRecipe(List.of(ingredient("salt", " ")))).containsExactly("salt");
    }

    @Test
    void generatedLinesUseNameAndAmountAndDropABlankAmount() {
        assertThat(forGenerated(stored(new StoredIngredient("rice", "1 cup", false), new StoredIngredient("salt", "", false))))
                .containsExactly("rice | 1 cup", "salt");
    }

    @Test
    void linesAreCutTo200Characters() {
        assertThat(forRecipe(List.of(ingredient("a", "b".repeat(500)))).get(0)).hasSize(200);
    }

    @Test
    void missingIngredientListIsEmpty() {
        assertThat(forGenerated(storedWithNullIngredients())).isEmpty();
    }

    @Test
    void numberedCountsFromOne() {
        assertThat(numbered(List.of("a", "b"))).isEqualTo("1. a\n2. b");
    }

    @Test
    void hashIsSha256HexOfTheNumberedLines() {
        assertThat(hash(List.of("a"))).hasSize(64).isEqualTo(hash(List.of("a"))).isNotEqualTo(hash(List.of("b")));
        assertThat(hash(List.of("a"))).isEqualTo("5a7435de8cde742d747049fb0b4a1b91c14d30e94a6c7acf0dcb71c3f1058701");
    }
}
