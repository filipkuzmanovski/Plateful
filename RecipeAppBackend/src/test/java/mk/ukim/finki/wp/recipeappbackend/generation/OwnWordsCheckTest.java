package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OwnWordsCheckTest {

    private static final String TWELVE =
            "mix the flour and the butter until the dough comes together nicely";
    private static final String ELEVEN = "mix the flour and the butter until the dough comes together";

    private static RecipeDraft draft(String description, String... steps) {
        return new RecipeDraft("Butter Dough", description, 4, 10, 20, null,
                List.of(new RecipeDraft.Ingredient("flour", "2 cups"), new RecipeDraft.Ingredient("butter", "1 cup"),
                        new RecipeDraft.Ingredient("salt", "1 pinch")),
                List.of(steps));
    }

    private static int count(String text) {
        return text.split(" ").length;
    }

    @Test
    void maxSharedRunIs11() {
        assertThat(OwnWordsCheck.MAX_SHARED_RUN).isEqualTo(11);
        assertThat(count(ELEVEN)).isEqualTo(11);
        assertThat(count(TWELVE)).isEqualTo(12);
    }

    @Test
    void elevenSharedWords_pass() {
        String post = "Caption: " + TWELVE + ". Enjoy!";
        Optional<DraftProblem> problem = OwnWordsCheck.check(
                draft("A buttery dough.", "Chop the onion.", ELEVEN + " slowly and gently"), post);
        assertThat(problem).isEmpty();
    }

    @Test
    void twelveSharedWords_fail() {
        Optional<DraftProblem> problem = OwnWordsCheck.check(
                draft("A buttery dough.", "Chop the onion.", "First, " + TWELVE + " and rest it."), TWELVE);
        assertThat(problem).isPresent();
        assertThat(problem.get().reason())
                .isEqualTo("Step 2 copies the post's wording. Rewrite it in your own words.");
    }

    @Test
    void caseAndPunctuationIgnored() {
        String post = "MIX the flour, and the butter -- until the dough comes together nicely!!";
        Optional<DraftProblem> problem = OwnWordsCheck.check(
                draft("A buttery dough.", "mix THE flour and the BUTTER until the dough (comes) together nicely.",
                        "Bake."), post);
        assertThat(problem).isPresent();
        assertThat(problem.get().reason()).startsWith("Step 1 copies");
    }

    @Test
    void descriptionIsChecked() {
        Optional<DraftProblem> problem = OwnWordsCheck.check(
                draft(TWELVE + ".", "Chop the onion.", "Bake it."), TWELVE);
        assertThat(problem).isPresent();
        assertThat(problem.get().reason())
                .isEqualTo("The description copies the post's wording. Rewrite it in your own words.");
    }

    @Test
    void ingredientsAndTitleNotChecked() {
        String post = "butter flour salt sugar eggs milk vanilla yeast water honey oil cocoa";
        RecipeDraft d = new RecipeDraft("butter flour salt sugar eggs milk vanilla yeast water honey oil cocoa",
                "A dough.", 4, 10, 20, null,
                List.of(new RecipeDraft.Ingredient("butter flour salt sugar eggs milk vanilla yeast water honey oil",
                                "cocoa"),
                        new RecipeDraft.Ingredient("flour", "2 cups"), new RecipeDraft.Ingredient("salt", "1")),
                List.of("Chop the onion.", "Bake it."));
        assertThat(OwnWordsCheck.check(d, post)).isEmpty();
    }

    @Test
    void emptyPostText_passes() {
        RecipeDraft d = draft(TWELVE, TWELVE, TWELVE);
        assertThat(OwnWordsCheck.check(d, "")).isEmpty();
        assertThat(OwnWordsCheck.check(d, null)).isEmpty();
        assertThat(OwnWordsCheck.check(d, "   \n ")).isEmpty();
    }
}
