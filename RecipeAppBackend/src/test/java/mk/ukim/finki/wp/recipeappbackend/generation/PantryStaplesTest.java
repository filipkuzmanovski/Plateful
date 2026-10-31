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
}
