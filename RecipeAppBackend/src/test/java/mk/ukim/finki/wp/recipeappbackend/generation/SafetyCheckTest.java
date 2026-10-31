package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import mk.ukim.finki.wp.recipeappbackend.food.FoodRules;
import org.junit.jupiter.api.Test;

class SafetyCheckTest {

    private final SafetyCheck safety = new SafetyCheck(new FoodRules(), new PantryStaples());

    private static final String[] SERVE = {"Mix everything.", "Serve hot."};

    private static RecipeDraft draft(List<String> ingredientNames, String... steps) {
        return new RecipeDraft("Dish", "A dish.", 2, 5, 10, null,
                ingredientNames.stream().map(n -> new RecipeDraft.Ingredient(n, "1")).toList(),
                List.of(steps));
    }

    private static UserFoodProfile profile(Set<String> allergens, Set<String> diets, Set<String> dislikes) {
        return new UserFoodProfile(allergens, diets, dislikes, Set.of(), List.of());
    }

    private static UserFoodProfile noRestrictions() {
        return profile(Set.of(), Set.of(), Set.of());
    }

    private static String reasonOf(SafetyCheck.SafetyResult r) {
        assertThat(r.problem()).isPresent();
        return r.problem().get().reason();
    }

    private static void assertNoForbiddenWords(String reason) {
        assertThat(reason.toLowerCase()).doesNotContain("allerg").doesNotContain("diet");
    }

    @Test
    void safeDraft_passes() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("tomato", "basil", "salt"), SERVE), noRestrictions(), Set.of("tomato", "basil"),
                SafetyCheck.Mode.ONLY_MINE);
        assertThat(r.problem()).isEmpty();
        assertThat(r.extras()).isEmpty();
    }

    @Test
    void allergenInName_isRejected() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("tomato", "butter", "pasta"), SERVE),
                profile(Set.of("milk"), Set.of(), Set.of()), Set.of(), SafetyCheck.Mode.ANY);
        String reason = reasonOf(r);
        assertThat(reason).isEqualTo("The recipe used butter, which is not allowed.");
        assertNoForbiddenWords(reason);
    }

    @Test
    void allergenOnlyInStep_isRejected() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("tomato", "pasta", "basil"), "Cook the pasta.", "Serve with a dollop of cream."),
                profile(Set.of("milk"), Set.of(), Set.of()), Set.of(), SafetyCheck.Mode.ANY);
        String reason = reasonOf(r);
        assertThat(reason).contains("cream").contains("not allowed");
        assertNoForbiddenWords(reason);
    }

    @Test
    void allergenMaskedByException_isNotRejected() {
        // "peanut butter" is not milk: the milk row masks it.
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("peanut butter", "bread", "banana"), SERVE),
                profile(Set.of("milk"), Set.of(), Set.of()), Set.of(), SafetyCheck.Mode.ANY);
        assertThat(r.problem()).isEmpty();
    }

    @Test
    void dietViolation_isRejected() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("pasta", "bacon", "tomato"), SERVE),
                profile(Set.of(), Set.of("vegetarian"), Set.of()), Set.of(), SafetyCheck.Mode.ANY);
        String reason = reasonOf(r);
        assertThat(reason).isEqualTo("The recipe used bacon, which is not allowed.");
        assertNoForbiddenWords(reason);
    }

    @Test
    void dietViolationOnlyInStep_isRejected() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("pasta", "tomato", "basil"), "Boil the pasta.", "Top with crispy bacon."),
                profile(Set.of(), Set.of("vegetarian"), Set.of()), Set.of(), SafetyCheck.Mode.ANY);
        assertThat(reasonOf(r)).contains("bacon").contains("not allowed");
    }

    @Test
    void dislikeWholeWord_isRejected() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("pasta", "green pea", "tomato"), SERVE),
                profile(Set.of(), Set.of(), Set.of("pea")), Set.of(), SafetyCheck.Mode.ANY);
        String reason = reasonOf(r);
        assertThat(reason).contains("green pea").contains("not allowed");
        assertNoForbiddenWords(reason);
    }

    @Test
    void dislikeInsideAnotherWord_isAllowed() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("pasta", "peanut", "tomato"), "Chop the peanuts.", "Serve."),
                profile(Set.of(), Set.of(), Set.of("pea")), Set.of(), SafetyCheck.Mode.ANY);
        assertThat(r.problem()).isEmpty();
    }

    @Test
    void dislikeInStep_isRejectedOnCleanedText() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("pasta", "tomato", "basil"), "Boil the pasta.", "Stir in the peas, then serve."),
                profile(Set.of(), Set.of(), Set.of("pea")), Set.of(), SafetyCheck.Mode.ANY);
        assertThat(reasonOf(r)).contains("pea").contains("not allowed");
    }

    @Test
    void dislikeInStepWithPunctuation_isRejected() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("pasta", "tomato", "basil"), "Boil the pasta.", "Add (pea) and mix."),
                profile(Set.of(), Set.of(), Set.of("pea")), Set.of(), SafetyCheck.Mode.ANY);
        assertThat(r.problem()).isPresent();
    }

    @Test
    void flourStillBreaksGlutenFree() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("flour", "egg", "tomato"), SERVE),
                profile(Set.of(), Set.of("gluten_free"), Set.of()), Set.of("egg", "tomato"),
                SafetyCheck.Mode.STAPLES_AND_EXTRAS);
        assertThat(reasonOf(r)).contains("flour").contains("not allowed");
    }

    @Test
    void cherryTomato_coveredByTomato() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("cherry tomato", "basil", "salt"), SERVE), noRestrictions(),
                Set.of("tomato", "basil"), SafetyCheck.Mode.ONLY_MINE);
        assertThat(r.problem()).isEmpty();
        assertThat(r.extras()).isEmpty();
    }

    @Test
    void userIngredientsAreNormalisedBeforeMatching() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("tomato", "basil", "salt"), SERVE), noRestrictions(),
                Set.of("Tomatoes", "Basil"), SafetyCheck.Mode.ONLY_MINE);
        assertThat(r.problem()).isEmpty();
    }

    @Test
    void extraVirginOliveOil_coveredByStaple() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("tomato", "extra virgin olive oil", "basil"), SERVE), noRestrictions(),
                Set.of("tomato", "basil"), SafetyCheck.Mode.ONLY_MINE);
        assertThat(r.problem()).isEmpty();
        assertThat(r.extras()).isEmpty();
    }

    @Test
    void onlyMine_anyExtra_isRejected() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("tomato", "basil", "mozzarella"), SERVE), noRestrictions(),
                Set.of("tomato", "basil"), SafetyCheck.Mode.ONLY_MINE);
        assertThat(reasonOf(r)).contains("mozzarella");
        assertThat(r.extras()).containsExactly("mozzarella");
    }

    @Test
    void fourExtras_rejected_threeAllowed() {
        Set<String> mine = Set.of("tomato");
        SafetyCheck.SafetyResult three = safety.check(
                draft(List.of("tomato", "basil", "mozzarella", "garlic"), SERVE), noRestrictions(), mine,
                SafetyCheck.Mode.STAPLES_AND_EXTRAS);
        assertThat(three.problem()).isEmpty();
        assertThat(three.extras()).containsExactlyInAnyOrder("basil", "mozzarella", "garlic");

        SafetyCheck.SafetyResult four = safety.check(
                draft(List.of("tomato", "basil", "mozzarella", "garlic", "zucchini"), SERVE), noRestrictions(),
                mine, SafetyCheck.Mode.STAPLES_AND_EXTRAS);
        assertThat(four.extras()).hasSize(4);
        String reason = reasonOf(four);
        assertThat(reason).contains("basil").contains("zucchini");
        assertNoForbiddenWords(reason);
    }

    @Test
    void anyMode_hasNoCoverageRule() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("tomato", "basil", "mozzarella", "garlic", "zucchini"), SERVE), noRestrictions(),
                Set.of(), SafetyCheck.Mode.ANY);
        assertThat(r.problem()).isEmpty();
        assertThat(r.extras()).isEmpty();
    }

    @Test
    void safetyIsCheckedBeforeCoverage() {
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("tomato", "peanut", "basil"), SERVE),
                profile(Set.of("peanuts"), Set.of(), Set.of()), Set.of("tomato"), SafetyCheck.Mode.ONLY_MINE);
        assertThat(reasonOf(r)).isEqualTo("The recipe used peanut, which is not allowed.");
    }

    @Test
    void conflicts_namesAllergyLabel() {
        List<SafetyCheck.Conflict> conflicts =
                safety.conflicts(List.of("Peanut butter"), profile(Set.of("peanuts"), Set.of(), Set.of()));
        assertThat(conflicts).containsExactly(new SafetyCheck.Conflict("Peanut butter", "allergy: Peanuts"));
    }

    @Test
    void conflicts_dietAndDislikeAndSafe() {
        UserFoodProfile p = profile(Set.of(), Set.of("vegetarian"), Set.of("pea"));
        Collection<String> requested = List.of("Bacon", "green peas", "tomato");
        assertThat(safety.conflicts(requested, p)).containsExactly(
                new SafetyCheck.Conflict("Bacon", "diet: Vegetarian"),
                new SafetyCheck.Conflict("green peas", "dislike"));
    }

    @Test
    void conflicts_noneForSafeIngredients() {
        assertThat(safety.conflicts(List.of("tomato", "basil"),
                profile(Set.of("peanuts"), Set.of("vegan"), Set.of("pea")))).isEmpty();
    }

    @Test
    void userFoodProfile_ofPreferences_normalisesNames() {
        var prefs = mk.ukim.finki.wp.recipeappbackend.model.entities.UserPreferences.builder()
                .allergens(List.of("milk")).diets(List.of("vegan"))
                .dislikedIngredients(List.of("Green Peas", "!!")).likedIngredients(List.of("Tomatoes"))
                .likedCuisines(List.of("italian")).build();
        UserFoodProfile p = UserFoodProfile.of(prefs);
        assertThat(p.allergens()).containsExactly("milk");
        assertThat(p.diets()).containsExactly("vegan");
        assertThat(p.dislikes()).containsExactly("green pea");
        assertThat(p.likedIngredients()).containsExactly("tomato");
        assertThat(p.likedCuisines()).containsExactly("italian");
    }

    // --- ingredient names with punctuation or descriptors -------------------------------------

    private SafetyCheck.SafetyResult checkIngredient(String name, UserFoodProfile p) {
        return safety.check(draft(List.of("tomato", name, "basil"), SERVE), p, Set.of(), SafetyCheck.Mode.ANY);
    }

    @Test
    void punctuatedNames_stillHitAllergens() {
        assertThat(checkIngredient("Almonds, sliced", profile(Set.of("tree_nuts"), Set.of(), Set.of())).problem())
                .isPresent();
        assertThat(checkIngredient("Eggs, beaten", profile(Set.of("eggs"), Set.of(), Set.of())).problem())
                .isPresent();
        assertThat(checkIngredient("Anchovies in oil", profile(Set.of("fish"), Set.of(), Set.of())).problem())
                .isPresent();
        SafetyCheck.SafetyResult slash =
                checkIngredient("cashews/peanuts", profile(Set.of("peanuts"), Set.of(), Set.of()));
        String reason = reasonOf(slash);
        assertThat(reason).contains("not allowed");
        assertNoForbiddenWords(reason);
    }

    @Test
    void punctuatedNames_stillHitDislikes() {
        assertThat(checkIngredient("Peas, frozen", profile(Set.of(), Set.of(), Set.of("pea"))).problem())
                .isPresent();
    }

    @Test
    void punctuatedNames_stillHitDiets() {
        assertThat(checkIngredient("Bacon, diced", profile(Set.of(), Set.of("vegan"), Set.of())).problem())
                .isPresent();
    }

    @Test
    void punctuatedName_coveredByUsersIngredient_andExtrasAreReportedAsText() {
        SafetyCheck.SafetyResult covered = safety.check(
                draft(List.of("Almonds, sliced", "tomato", "basil"), SERVE), noRestrictions(),
                Set.of("almond", "tomato", "basil"), SafetyCheck.Mode.ONLY_MINE);
        assertThat(covered.problem()).isEmpty();

        SafetyCheck.SafetyResult extra = safety.check(
                draft(List.of("Almonds, sliced", "tomato", "basil"), SERVE), noRestrictions(),
                Set.of("tomato", "basil"), SafetyCheck.Mode.STAPLES_AND_EXTRAS);
        assertThat(extra.extras()).containsExactly("almond sliced");
    }

    @Test
    void conflicts_flagPunctuatedNames() {
        assertThat(safety.conflicts(List.of("Almonds, chopped"), profile(Set.of("tree_nuts"), Set.of(), Set.of())))
                .containsExactly(new SafetyCheck.Conflict("Almonds, chopped", "allergy: Tree nuts"));
    }

    // --- staples cover only with descriptors ----------------------------------------------------

    @Test
    void stapleDescriptors_areCovered_otherIngredientsAreExtras() {
        UserFoodProfile none = noRestrictions();
        for (String covered : new String[] {"extra virgin olive oil", "freshly ground black pepper",
                "all purpose flour", "unsalted butter", "pepper"}) {
            SafetyCheck.SafetyResult r = safety.check(draft(List.of("tomato", covered, "basil"), SERVE), none,
                    Set.of("tomato", "basil"), SafetyCheck.Mode.ONLY_MINE);
            assertThat(r.problem()).as(covered).isEmpty();
            assertThat(r.extras()).as(covered).isEmpty();
        }
        for (String extra : new String[] {"bell pepper", "sesame oil"}) {
            SafetyCheck.SafetyResult r = safety.check(draft(List.of("tomato", extra, "basil"), SERVE), none,
                    Set.of("tomato", "basil"), SafetyCheck.Mode.STAPLES_AND_EXTRAS);
            assertThat(r.extras()).as(extra).containsExactly(extra);
        }
    }

    // --- all draft text is scanned ----------------------------------------------------------------

    private static RecipeDraft withText(String title, String description, String amount) {
        return new RecipeDraft(title, description, 2, 5, 10, null,
                List.of(new RecipeDraft.Ingredient("noodles", amount), new RecipeDraft.Ingredient("basil", "1"),
                        new RecipeDraft.Ingredient("pasta", "1")),
                List.of("Mix.", "Serve."));
    }

    @Test
    void amountTitleAndDescription_areScanned() {
        UserFoodProfile milk = profile(Set.of("milk"), Set.of(), Set.of());
        String amount = reasonOf(safety.check(withText("Dish", "A dish.", "1 cup (or use cream)"), milk, Set.of(),
                SafetyCheck.Mode.ANY));
        assertThat(amount).isEqualTo("The recipe text mentioned cream, which is not allowed.");
        assertNoForbiddenWords(amount);

        assertThat(reasonOf(safety.check(withText("Dish", "Finished with a dollop of yogurt", "1"), milk, Set.of(),
                SafetyCheck.Mode.ANY))).contains("yogurt").contains("not allowed");

        assertThat(reasonOf(safety.check(withText("Creamy Peanut Noodles", "A dish.", "1"),
                profile(Set.of("peanuts"), Set.of(), Set.of()), Set.of(), SafetyCheck.Mode.ANY)))
                .contains("peanut").contains("not allowed");
    }

    // --- dislikes stored in ingredient form -----------------------------------------------------

    @Test
    void multiWordDislike_matchesFreeTextForm() {
        UserFoodProfile p = UserFoodProfile.of(
                mk.ukim.finki.wp.recipeappbackend.model.entities.UserPreferences.builder()
                        .dislikedIngredients(List.of("Brussels sprouts")).build());
        assertThat(p.dislikes()).containsExactly("brussels sprout");
        SafetyCheck.SafetyResult r = safety.check(
                draft(List.of("pasta", "tomato", "basil"), "Roast the Brussels sprouts.", "Serve."), p, Set.of(),
                SafetyCheck.Mode.ANY);
        assertThat(r.problem()).isPresent();
        assertThat(checkIngredient("Brussels sprouts", p).problem()).isPresent();
    }

    // --- fail closed ---------------------------------------------------------------------------------

    @Test
    void unknownAllergenOrDietKey_throws() {
        UserFoodProfile bad = profile(Set.of("peanutz"), Set.of(), Set.of());
        assertThatThrownBy(() -> safety.check(
                draft(List.of("tomato", "basil", "pasta"), SERVE), bad, Set.of(), SafetyCheck.Mode.ANY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> safety.conflicts(List.of("tomato"), bad))
                .isInstanceOf(IllegalArgumentException.class);
        UserFoodProfile badDiet = profile(Set.of(), Set.of("carnivore"), Set.of());
        assertThatThrownBy(() -> safety.check(
                draft(List.of("tomato", "basil", "pasta"), SERVE), badDiet, Set.of(), SafetyCheck.Mode.ANY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowed_checksALineInBothForms_andFailsClosed() {
        UserFoodProfile peanuts = profile(Set.of("peanuts"), Set.of(), Set.of());
        assertThat(safety.allowed("1 cup peanuts/almonds", peanuts)).isFalse();
        assertThat(safety.allowed("1 cup rice", peanuts)).isTrue();
        assertThat(safety.allowed("2 tbsp ghee", profile(Set.of("milk"), Set.of(), Set.of()))).isFalse();
        assertThat(safety.allowed("200 g chicken", profile(Set.of(), Set.of("vegetarian"), Set.of()))).isFalse();
        assertThat(safety.allowed("1 cup chopped olives", profile(Set.of(), Set.of(), Set.of("olive")))).isFalse();
        assertThatThrownBy(() -> safety.allowed("rice", profile(Set.of("nope"), Set.of(), Set.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
