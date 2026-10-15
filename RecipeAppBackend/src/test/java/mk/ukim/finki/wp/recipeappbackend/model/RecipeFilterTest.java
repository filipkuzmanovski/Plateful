package mk.ukim.finki.wp.recipeappbackend.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeFilterTest {

    private static RecipeFilter withQuery(String q) {
        return new RecipeFilter(null, null, null, null, null, null, null, null, null, q);
    }

    @Test
    void nullQueryIsEmpty() {
        assertThat(withQuery(null).isEmpty()).isTrue();
    }

    @Test
    void blankQueryIsEmpty() {
        assertThat(withQuery("").isEmpty()).isTrue();
        assertThat(withQuery("   ").isEmpty()).isTrue();
    }

    @Test
    void realQueryIsNotEmpty() {
        assertThat(withQuery("garlic").isEmpty()).isFalse();
    }
}
