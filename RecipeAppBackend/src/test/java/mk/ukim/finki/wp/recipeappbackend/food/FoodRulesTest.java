package mk.ukim.finki.wp.recipeappbackend.food;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FoodRulesTest {

    private static final String HEADER = "kind,key,label,terms,exceptions\n";
    private static final Set<String> ALL_DIETS = Set.of("vegetarian", "vegan", "gluten_free", "dairy_free");

    private final FoodRules rules = new FoodRules();

    private static FoodRules from(String body) {
        return new FoodRules(new StringReader(HEADER + body));
    }

    // --- shipped file ---------------------------------------------------------

    @Test
    void hasTheFourteenAllergensInOrder() {
        assertThat(rules.allergens()).hasSize(14);
        assertThat(rules.allergens().get(0)).isEqualTo(new Allergen("gluten", "Cereals containing gluten"));
        assertThat(rules.allergens()).extracting(Allergen::key).containsExactly(
                "gluten", "crustaceans", "eggs", "fish", "peanuts", "soy", "milk",
                "tree_nuts", "celery", "mustard", "sesame", "sulphites", "lupin", "molluscs");
        assertThat(rules.allergenKeys()).containsExactlyElementsOf(
                rules.allergens().stream().map(Allergen::key).toList());
    }

    @Test
    void hasTheFourDietKeys() {
        assertThat(rules.dietKeys()).containsExactly("vegetarian", "vegan", "gluten_free", "dairy_free");
    }

    @Test
    void butterIsMilk() {
        assertThat(rules.allergensIn(List.of("unsalted butter"))).containsExactly("milk");
    }

    @Test
    void matchesWholeWordsOnly() {
        assertThat(rules.allergensIn(List.of("butternut squash"))).isEmpty();
    }

    @Test
    void peanutButterIsPeanutsNotMilk() {
        assertThat(rules.allergensIn(List.of("peanut butter"))).containsExactly("peanuts");
    }

    @Test
    void coconutMilkIsNotMilk() {
        assertThat(rules.allergensIn(List.of("coconut milk"))).isEmpty();
    }

    @Test
    void pluralInputIsNormalised() {
        assertThat(rules.allergensIn(List.of("eggs"))).containsExactly("eggs");
    }

    @Test
    void namesAreNormalisedBeforeMatching() {
        assertThat(rules.allergensIn(List.of("Unsalted  Butter"))).containsExactly("milk");
        assertThat(rules.allergensIn(List.of("Crème Fraîche"))).containsExactly("milk");
    }

    @Test
    void namesThatNormaliseToNothingAreIgnored() {
        assertThat(rules.allergensIn(List.of("!!!", "123", ""))).isEmpty();
        assertThat(rules.dietViolations(List.of("!!!"), Set.of("vegan"))).isEmpty();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "almond milk, tree_nuts",
            "oyster mushrooms, ''",
            "crab apples, ''",
            "butter beans, ''",
            "eggplant, ''",
            "tiger prawns, crustaceans",
            "mussels, molluscs",
            "tahini, sesame",
    })
    void knownNames(String name, String expected) {
        Set<String> expectedKeys = expected == null || expected.isEmpty() ? Set.of() : Set.of(expected);
        assertThat(rules.allergensIn(List.of(name))).isEqualTo(expectedKeys);
    }

    @Test
    void honeyBreaksVeganButNotVegetarian() {
        assertThat(rules.dietViolations(List.of("honey"), Set.of("vegan", "vegetarian"))).isEqualTo(Set.of("vegan"));
    }

    @Test
    void onlyTheRequestedDietsAreChecked() {
        assertThat(rules.dietViolations(List.of("butter", "flour"), Set.of("gluten_free"))).containsExactly("gluten_free");
        assertThat(rules.dietViolations(List.of("butter", "flour"), ALL_DIETS))
                .containsExactlyInAnyOrder("vegan", "gluten_free", "dairy_free");
    }

    @Test
    void coconutMilkIsDairyFreeAndVegan() {
        assertThat(rules.dietViolations(List.of("coconut milk"), Set.of("dairy_free", "vegan"))).isEmpty();
    }

    @Test
    void exceptionsOnlyMaskTheirOwnRowsTerms() {
        assertThat(rules.dietViolations(List.of("honey roasted peanut butter"), ALL_DIETS)).containsExactly("vegan");
    }

    @Test
    void plainVegetablesFitEveryDiet() {
        assertThat(rules.dietViolations(List.of("carrot", "olive oil", "rice"), ALL_DIETS)).isEmpty();
    }

    // --- fail fast on a broken file ----------------------------------------------

    @Test
    void unknownReferenceFailsFast() {
        assertThatThrownBy(() -> from("diet,vegan,Vegan,@nope|honey,\n")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateKeyFailsFast() {
        assertThatThrownBy(() -> from("allergen,eggs,Eggs,egg,\nallergen,eggs,Eggs again,mayonnaise,\n"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void referenceCycleFailsFast() {
        assertThatThrownBy(() -> from("diet,a,A,@b,\ndiet,b,B,@a,\n")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void termThatNormalisesToNothingFailsFast() {
        assertThatThrownBy(() -> from("allergen,eggs,Eggs,egg|123,\n")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongFieldCountFailsFast() {
        assertThatThrownBy(() -> from("allergen,eggs,Eggs,egg\n")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongHeaderFailsFast() {
        assertThatThrownBy(() -> new FoodRules(new StringReader("kind,key,label\nallergen,eggs,Eggs,egg,\n")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownKindFailsFast() {
        assertThatThrownBy(() -> from("cheese,eggs,Eggs,egg,\n")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void referenceInExceptionsFailsFast() {
        assertThatThrownBy(() -> from("allergen,milk,Milk,milk,\nallergen,eggs,Eggs,egg,@milk\n"))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- reference semantics -------------------------------------------------------

    @Test
    void referenceImportsTermsAndExceptions() {
        FoodRules r = from("allergen,milk,Milk,milk|butter,peanut butter\n"
                + "diet,dairy_free,Dairy-free,@milk|whey,\n");
        Set<String> dairyFree = Set.of("dairy_free");
        assertThat(r.dietViolations(List.of("butter"), dairyFree)).containsExactly("dairy_free");
        assertThat(r.dietViolations(List.of("whey protein"), dairyFree)).containsExactly("dairy_free");
        assertThat(r.dietViolations(List.of("peanut butter"), dairyFree)).isEmpty();
    }

    @Test
    void forwardReferenceIsFine() {
        FoodRules r = from("diet,d,D,@a,\nallergen,a,A,egg,\n");
        assertThat(r.dietViolations(List.of("egg"), Set.of("d"))).containsExactly("d");
    }

    @Test
    void importedExceptionDoesNotHideTheImportingRowsOwnTerms() {
        FoodRules r = from("allergen,milk,Milk,milk|butter,peanut butter\n"
                + "diet,vegan,Vegan,@milk|honey,\n");
        assertThat(r.dietViolations(List.of("peanut butter"), Set.of("vegan"))).isEmpty();
        assertThat(r.dietViolations(List.of("peanut butter honey"), Set.of("vegan"))).containsExactly("vegan");
    }

    @Test
    void toleratesBomCrlfAndBlankLines() {
        FoodRules r = new FoodRules(new StringReader("\uFEFF" + HEADER.replace("\n", "\r\n")
                + "\r\nallergen,eggs,Eggs,egg|mayonnaise,\r\n\r\n"));
        assertThat(r.allergens()).containsExactly(new Allergen("eggs", "Eggs"));
        assertThat(r.allergensIn(List.of("mayonnaise"))).containsExactly("eggs");
    }
}
