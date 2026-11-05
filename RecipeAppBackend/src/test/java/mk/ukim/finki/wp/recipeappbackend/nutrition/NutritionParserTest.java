package mk.ukim.finki.wp.recipeappbackend.nutrition;

import static mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionCheck.problem;
import static mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionParser.parse;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NutritionParserTest {

    @Test
    void readsItems() {
        assertThat(parse("{\"items\":[{\"line\":1,\"grams\":10,\"calories\":40,\"protein\":1,\"fat\":2,\"carbs\":3}]}").orElseThrow())
                .containsExactly(new NutritionItem(1, 10, 40, 1, 2, 3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[]", "{}", "{\"items\":{}}",
            "{\"items\":[{\"line\":1.5,\"grams\":1,\"calories\":1,\"protein\":1,\"fat\":1,\"carbs\":1}]}",
            "{\"items\":[{\"line\":1,\"grams\":\"10\",\"calories\":1,\"protein\":1,\"fat\":1,\"carbs\":1}]}",
            "{\"items\":[{\"line\":1,\"calories\":1,\"protein\":1,\"fat\":1,\"carbs\":1}]}"})
    void anythingElseIsEmpty(String json) {
        assertThat(parse(json)).isEmpty();
    }

    @Test
    void nullIsEmpty() {
        assertThat(parse(null)).isEmpty();
    }

    @Test
    void hugeNumbersReachTheCheckAsNotFinite() {
        var items = parse("{\"items\":[{\"line\":1,\"grams\":1e999,\"calories\":1,\"protein\":1,\"fat\":1,\"carbs\":1}]}");
        assertThat(items).hasValueSatisfying(i -> assertThat(problem(i, 1, 1)).isPresent());
    }
}
