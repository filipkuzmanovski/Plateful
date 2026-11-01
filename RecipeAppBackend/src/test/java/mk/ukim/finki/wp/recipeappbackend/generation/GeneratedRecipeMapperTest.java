package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import mk.ukim.finki.wp.recipeappbackend.food.FoodRules;
import org.junit.jupiter.api.Test;

class GeneratedRecipeMapperTest {

    private final GeneratedRecipeMapper mapper = new GeneratedRecipeMapper(new FoodRules());

    private static RecipeDraft draft(String... ingredientNames) {
        return new RecipeDraft("Test Dish", "A dish.", 2, 5, 10, null,
                java.util.Arrays.stream(ingredientNames).map(n -> new RecipeDraft.Ingredient(n, "1 cup")).toList(),
                List.of("Mix.", "Serve."));
    }

    @Test
    void eggsWithAPreparationNote_countAsEggs_andBreakOnlyVegan() {
        StoredRecipe stored = mapper.fromDraft(draft("Eggs, beaten", "spinach"), Set.of());

        assertThat(stored.allergens()).contains("eggs");
        assertThat(stored.diets().vegan()).isFalse();
        assertThat(stored.diets().vegetarian()).isTrue();
    }

    @Test
    void almondsWithAPreparationNote_countAsTreeNuts() {
        StoredRecipe stored = mapper.fromDraft(draft("Almonds, sliced", "oats"), Set.of());

        assertThat(stored.allergens()).contains("tree_nuts");
    }

    @Test
    void slashSeparatedNuts_countAsTreeNuts() {
        StoredRecipe stored = mapper.fromDraft(draft("walnuts/pecans", "oats"), Set.of());

        assertThat(stored.allergens()).contains("tree_nuts");
    }

    @Test
    void plainIngredientForm_stillCounts() {
        StoredRecipe stored = mapper.fromDraft(draft("butter", "flour"), Set.of());

        assertThat(stored.allergens()).contains("milk", "gluten");
        assertThat(stored.diets().vegan()).isFalse();
        assertThat(stored.diets().dairyFree()).isFalse();
        assertThat(stored.diets().glutenFree()).isFalse();
        assertThat(stored.diets().vegetarian()).isTrue();
    }

    @Test
    void allergensAreListedInTheOrderOfTheRulesFile() {
        StoredRecipe stored = mapper.fromDraft(draft("Eggs, beaten", "flour"), Set.of());

        assertThat(stored.allergens()).containsExactly("gluten", "eggs");
    }

    @Test
    void plantOnlyRecipe_keepsEveryDiet_andHasNoAllergens() {
        StoredRecipe stored = mapper.fromDraft(draft("rice", "black beans", "lime"), Set.of());

        assertThat(stored.allergens()).isEmpty();
        assertThat(stored.diets()).isEqualTo(new StoredRecipe.Diets(true, true, true, true));
    }

    @Test
    void extrasAreMarkedByTheirFreeTextName() {
        StoredRecipe stored = mapper.fromDraft(draft("Almonds, sliced", "oats"), Set.of("almond sliced"));

        assertThat(stored.ingredients()).extracting(StoredRecipe.StoredIngredient::extra).containsExactly(true, false);
    }

    @Test
    void toDraft_isTheInverseOfFromDraftForTheCheckedFields() {
        RecipeDraft original = draft("Eggs, beaten", "spinach");

        RecipeDraft back = mapper.toDraft(mapper.fromDraft(original, Set.of()));

        assertThat(back).isEqualTo(original);
    }

    @Test
    void toDraft_turnsMissingPartsIntoBlanks() {
        StoredRecipe odd = new StoredRecipe(null, null, 2, 5, 10, null,
                java.util.Arrays.asList(new StoredRecipe.StoredIngredient(null, null, false), null),
                java.util.Arrays.asList("Mix.", null), null, null);

        RecipeDraft back = mapper.toDraft(odd);

        assertThat(back.title()).isEmpty();
        assertThat(back.description()).isEmpty();
        assertThat(back.ingredients()).containsExactly(new RecipeDraft.Ingredient("", ""));
        assertThat(back.steps()).containsExactly("Mix.", "");
    }
}
