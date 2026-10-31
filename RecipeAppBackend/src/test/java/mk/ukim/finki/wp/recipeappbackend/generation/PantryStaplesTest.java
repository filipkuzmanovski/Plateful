package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PantryStaplesTest {

    private final PantryStaples staples = new PantryStaples();

    @Test
    void hasTheFifteenStaples() {
        assertThat(staples.names()).hasSize(15).contains("salt", "black pepper", "cornstarch", "baking soda");
    }

    @Test
    void recognisesStaplesByNormalisedName() {
        assertThat(staples.isStaple("olive oil")).isTrue();
        assertThat(staples.isStaple("sesame oil")).isFalse();
    }

    @Test
    void coversStapleDescriptorsButNotOtherIngredients() {
        assertThat(staples.covers("pepper")).isTrue();
        assertThat(staples.covers("extra virgin olive oil")).isTrue();
        assertThat(staples.covers("freshly ground black pepper")).isTrue();
        assertThat(staples.covers("all purpose flour")).isTrue();
        assertThat(staples.covers("unsalted butter")).isTrue();
        assertThat(staples.covers("bell pepper")).isFalse();
        assertThat(staples.covers("sesame oil")).isFalse();
        assertThat(staples.covers("chicken")).isFalse();
        assertThat(staples.covers("")).isFalse();
        assertThat(staples.covers(null)).isFalse();
    }

    @Test
    void stapleDoesNotCoverInTheReverseDirection() {
        // "oil" is a staple, but "olive" alone is not covered by "olive oil"
        assertThat(staples.covers("olive")).isFalse();
    }
}
