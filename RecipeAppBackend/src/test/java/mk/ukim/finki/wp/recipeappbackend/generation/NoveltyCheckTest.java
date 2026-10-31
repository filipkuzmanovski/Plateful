package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class NoveltyCheckTest {

    private final NoveltyCheck novelty = new NoveltyCheck(new PantryStaples());

    private static Set<String> names(String prefix, int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(i -> prefix + " " + (char) ('a' + i))
                .collect(Collectors.toSet());
    }

    private static RecipeDraft draft(String title, Set<String> ingredientNames) {
        return new RecipeDraft(title, "A dish.", 2, 5, 10, null,
                ingredientNames.stream().map(n -> new RecipeDraft.Ingredient(n, "1")).toList(),
                List.of("Mix.", "Serve."));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new java.util.LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    @Test
    void thresholds_areThePinnedConstants() {
        assertThat(NoveltyCheck.INGREDIENT_THRESHOLD).isEqualTo(0.60);
        assertThat(NoveltyCheck.TITLE_THRESHOLD).isEqualTo(0.75);
    }

    @Test
    void ingredientJaccard_justBelowThreshold_passes() {
        // 10 shared of a union of 17 = 0.588
        Set<String> shared = names("shared", 0, 9);
        Set<String> draftOnly = names("draft only", 0, 3); // 4
        Set<String> otherOnly = names("other only", 0, 2); // 3
        RecipeDraft d = draft("Alpha Dish", union(shared, draftOnly));
        ComparedRecipe c = new ComparedRecipe("Zeta Meal", union(shared, otherOnly));
        NoveltyCheck.NoveltyResult r = novelty.check(d, List.of(c));
        assertThat(r.score()).isCloseTo(10.0 / 17.0, within(1e-9));
        assertThat(r.problem()).isEmpty();
    }

    @Test
    void ingredientJaccard_exactlyAtThreshold_fails() {
        // 3 shared of a union of 5 = 0.60
        Set<String> shared = names("shared", 0, 2);
        RecipeDraft d = draft("Alpha Dish", union(shared, names("draft only", 0, 0)));
        ComparedRecipe c = new ComparedRecipe("Zeta Meal", union(shared, names("other only", 0, 0)));
        NoveltyCheck.NoveltyResult r = novelty.check(d, List.of(c));
        assertThat(r.score()).isEqualTo(0.60);
        assertThat(r.problem()).isPresent();
        assertThat(r.problem().get().reason())
                .isEqualTo("Too similar to 'Zeta Meal'. Make something more different.");
    }

    @Test
    void titleJaccard_justBelowThreshold_passes() {
        // 5 shared of 7 = 0.714
        RecipeDraft d = draft("Aa Bb Cc Dd Ee Ff", Set.of("x one", "x two"));
        ComparedRecipe c = new ComparedRecipe("Aa Bb Cc Dd Ee Gg", Set.of("y one", "y two"));
        NoveltyCheck.NoveltyResult r = novelty.check(d, List.of(c));
        assertThat(r.problem()).isEmpty();
    }

    @Test
    void titleJaccard_exactlyAtThreshold_fails() {
        // 3 shared of 4 = 0.75
        RecipeDraft d = draft("Aa Bb Cc Dd", Set.of("x one", "x two"));
        ComparedRecipe c = new ComparedRecipe("Aa Bb Cc", Set.of("y one", "y two"));
        NoveltyCheck.NoveltyResult r = novelty.check(d, List.of(c));
        assertThat(r.problem()).isPresent();
        assertThat(r.problem().get().reason()).contains("'Aa Bb Cc'");
    }

    @Test
    void staplesAreRemovedBeforeJaccard() {
        Set<String> rest = Set.of("chicken", "tomato");
        RecipeDraft d = draft("Alpha Dish", union(rest, Set.of("salt", "olive oil", "butter")));
        ComparedRecipe c = new ComparedRecipe("Zeta Meal", union(rest, Set.of("flour", "sugar")));
        NoveltyCheck.NoveltyResult r = novelty.check(d, List.of(c));
        assertThat(r.score()).isEqualTo(1.0);
        assertThat(r.problem()).isPresent();
    }

    @Test
    void emptyAfterStaples_skipsTheIngredientTest() {
        RecipeDraft d = draft("Alpha Dish", Set.of("salt", "water", "sugar"));
        ComparedRecipe c = new ComparedRecipe("Zeta Meal", Set.of("salt", "water", "sugar"));
        NoveltyCheck.NoveltyResult r = novelty.check(d, List.of(c));
        assertThat(r.score()).isEqualTo(0.0);
        assertThat(r.problem()).isEmpty();

        RecipeDraft d2 = draft("Alpha Dish", Set.of("chicken", "rice"));
        ComparedRecipe emptyComparison = new ComparedRecipe("Zeta Meal", Set.of("salt"));
        assertThat(novelty.check(d2, List.of(emptyComparison)).problem()).isEmpty();
    }

    @Test
    void stopWordsAreRemovedFromTitles() {
        RecipeDraft d = draft("Easy Chicken with Rice", Set.of("x one", "x two"));
        ComparedRecipe c = new ComparedRecipe("Chicken Rice", Set.of("y one", "y two"));
        NoveltyCheck.NoveltyResult r = novelty.check(d, List.of(c));
        assertThat(r.problem()).isPresent();
    }

    @Test
    void titleThatIsAllStopWords_skipsTheTitleTest() {
        RecipeDraft d = draft("The Best Easy Recipe", Set.of("x one", "x two"));
        ComparedRecipe c = new ComparedRecipe("A Quick Homemade Recipe", Set.of("y one", "y two"));
        assertThat(novelty.check(d, List.of(c)).problem()).isEmpty();
    }

    @Test
    void problemNamesTheClosestTitle() {
        Set<String> rest = Set.of("chicken", "tomato", "rice", "onion", "garlic");
        RecipeDraft d = draft("Alpha Dish", rest);
        ComparedRecipe close = new ComparedRecipe("Chicken Tomato Bake",
                Set.of("chicken", "tomato", "rice", "onion", "garlic"));
        ComparedRecipe closer = new ComparedRecipe("Chicken Tomato Rice Bake",
                Set.of("chicken", "tomato", "rice", "onion", "garlic", "pea"));
        ComparedRecipe far = new ComparedRecipe("Fruit Salad", Set.of("apple", "pear"));
        NoveltyCheck.NoveltyResult r = novelty.check(d, List.of(far, closer, close));
        assertThat(r.problem()).isPresent();
        assertThat(r.problem().get().reason())
                .isEqualTo("Too similar to 'Chicken Tomato Bake'. Make something more different.");
        assertThat(r.score()).isEqualTo(1.0);
    }

    @Test
    void scoreIsTheHighestIngredientJaccard_evenWhenPassing() {
        RecipeDraft d = draft("Alpha Dish", Set.of("aa", "ab", "ac", "ad"));
        ComparedRecipe half = new ComparedRecipe("Zeta", Set.of("aa", "ab", "ba", "bb"));
        ComparedRecipe low = new ComparedRecipe("Eta", Set.of("aa", "ca", "cb", "cc"));
        NoveltyCheck.NoveltyResult r = novelty.check(d, List.of(low, half));
        assertThat(r.score()).isCloseTo(2.0 / 6.0, within(1e-9));
        assertThat(r.problem()).isEmpty();
    }

    @Test
    void noComparedRecipes_passes() {
        NoveltyCheck.NoveltyResult r = novelty.check(draft("Alpha Dish", Set.of("aa", "ab")), List.of());
        assertThat(r.score()).isEqualTo(0.0);
        assertThat(r.problem()).isEmpty();
    }
}
