package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.food.FoodRules;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
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

    private GeneratedRecipe rowOf(StoredRecipe recipe, String kind) {
        return GeneratedRecipe.builder()
                .id(UUID.randomUUID())
                .kind(kind)
                .title(recipe.title())
                .recipe(mapper.toDocument(recipe))
                .provider("test")
                .createdAt(OffsetDateTime.parse("2026-11-01T10:00:00Z"))
                .build();
    }

    @Test
    void postNote_roundTripsThroughTheDocument() {
        StoredRecipe stored = mapper.fromDraft(draft("rice", "lime"), Set.of());
        StoredRecipe.PostNote note = new StoredRecipe.PostNote("dish",
                List.of(new StoredRecipe.Swap("peanuts", "sunflower seeds")));

        GeneratedRecipeDto dto = mapper.toDto(rowOf(mapper.withPost(stored, note), "post"));

        assertThat(dto.kind()).isEqualTo("post");
        assertThat(dto.origin()).isEqualTo("dish");
        assertThat(dto.swaps()).containsExactly(new StoredRecipe.Swap("peanuts", "sunflower seeds"));
    }

    @Test
    void withPost_keepsEverythingElse() {
        StoredRecipe stored = mapper.fromDraft(draft("Eggs, beaten", "flour"), Set.of());

        StoredRecipe withNote = mapper.withPost(stored, new StoredRecipe.PostNote("recipe", List.of()));

        assertThat(withNote).usingRecursiveComparison().ignoringFields("post").isEqualTo(stored);
        assertThat(withNote.post()).isEqualTo(new StoredRecipe.PostNote("recipe", List.of()));
    }

    @Test
    void documentWithoutPost_readsAsNull() {
        StoredRecipe stored = mapper.fromDraft(draft("rice", "lime"), Set.of());
        Map<String, Object> document = new HashMap<>(mapper.toDocument(stored));
        document.remove("post");

        GeneratedRecipe row = rowOf(stored, "cook");
        row.setRecipe(document);
        GeneratedRecipeDto dto = mapper.toDto(row);

        assertThat(dto.origin()).isNull();
        assertThat(dto.swaps()).isEmpty();
    }

    @Test
    void cookRecipe_hasNoOrigin() {
        StoredRecipe stored = mapper.fromDraft(draft("rice", "lime"), Set.of());

        GeneratedRecipeDto dto = mapper.toDto(rowOf(stored, "cook"));

        assertThat(dto.origin()).isNull();
        assertThat(dto.swaps()).isEmpty();
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
