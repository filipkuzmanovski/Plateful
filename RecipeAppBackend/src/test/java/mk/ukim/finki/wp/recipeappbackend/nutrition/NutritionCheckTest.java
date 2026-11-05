package mk.ukim.finki.wp.recipeappbackend.nutrition;

import static mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionCheck.problem;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NutritionCheckTest {

    private static NutritionItem item(int line, double grams, double kcal, double protein, double fat, double carbs) {
        return new NutritionItem(line, grams, kcal, protein, fat, carbs);
    }

    @Test
    void linesMustBeOneToNOnce() {
        assertThat(problem(List.of(item(1, 100, 400, 100, 0, 0)), 2, 1)).hasValue("lines missing or repeated");
        assertThat(problem(List.of(item(1, 100, 400, 100, 0, 0), item(1, 100, 400, 100, 0, 0)), 2, 1)).isPresent();
        assertThat(problem(List.of(item(2, 100, 400, 100, 0, 0)), 1, 1)).isPresent();
    }

    @Test
    void negativeOrNotFiniteIsRejected() {
        assertThat(problem(List.of(item(1, 100, -1, 0, 0, 0)), 1, 1)).isPresent();
        assertThat(problem(List.of(item(1, Double.POSITIVE_INFINITY, 400, 100, 0, 0)), 1, 1)).isPresent();
        assertThat(problem(List.of(item(1, 100, Double.NaN, 0, 0, 0)), 1, 1)).isPresent();
    }

    @Test
    void macrosCannotOutweighTheIngredient() {
        assertThat(problem(List.of(item(1, 100, 424, 106, 0, 0)), 1, 1)).isEmpty();
        assertThat(problem(List.of(item(1, 100, 424.4, 106.1, 0, 0)), 1, 1)).isPresent();
    }

    @Test
    void macroLimitIs105PercentOfGramsPlusOne() {
        assertThat(problem(List.of(item(1, 0, 4, 1, 0, 0)), 1, 1)).isEmpty();
        assertThat(problem(List.of(item(1, 0, 4.4, 1.1, 0, 0)), 1, 1))
                .hasValue("line 1 has more protein, fat and carbs than its weight");
        assertThat(problem(List.of(item(1, 1000, 4204, 1051, 0, 0)), 1, 2)).isEmpty();
        assertThat(problem(List.of(item(1, 1000, 4208, 1052, 0, 0)), 1, 2))
                .hasValue("line 1 has more protein, fat and carbs than its weight");
    }

    @Test
    void atMost900KcalPer100GramsPlus5() {
        assertThat(problem(List.of(item(1, 100, 905, 0, 100, 0)), 1, 1)).isEmpty();
        assertThat(problem(List.of(item(1, 100, 905.1, 0, 100, 0)), 1, 1)).isPresent();
        assertThat(problem(List.of(item(1, 0, 5, 0, 0, 0)), 1, 1)).isEmpty();
        assertThat(problem(List.of(item(1, 0, 5.1, 0, 0, 0)), 1, 1)).isPresent();
    }

    @Test
    void recipeCaloriesMustMatchMacrosWithinTheLargerOf50KcalAnd20Percent() {
        assertThat(problem(List.of(item(1, 1000, 1000, 200, 0, 0)), 1, 1)).isEmpty();
        assertThat(problem(List.of(item(1, 1000, 1000, 199.7, 0, 0)), 1, 1)).isPresent();
        assertThat(problem(List.of(item(1, 100, 100, 12.5, 0, 0)), 1, 1)).isEmpty();
        assertThat(problem(List.of(item(1, 100, 100, 12.25, 0, 0)), 1, 1)).isPresent();
    }

    @Test
    void perServingCaloriesFrom1To3000() {
        assertThat(problem(List.of(item(1, 900, 3000, 750, 0, 0)), 1, 1)).isEmpty();
        assertThat(problem(List.of(item(1, 900, 3000.1, 750, 0, 0)), 1, 1)).isPresent();
        assertThat(problem(List.of(item(1, 10, 1, 0, 0, 0)), 1, 1)).isEmpty();
        assertThat(problem(List.of(item(1, 10, 0.9, 0, 0, 0)), 1, 1)).isPresent();
    }

    @Test
    void atMost3000GramsPerServing() {
        assertThat(problem(List.of(item(1, 3000, 100, 25, 0, 0)), 1, 1)).isEmpty();
        assertThat(problem(List.of(item(1, 3001, 100, 25, 0, 0)), 1, 1)).isPresent();
    }

    @Test
    void measuredCasesPass() {
        var beans = item(1, 1135, 1544, 96, 6, 276);
        var salt = item(2, 0, 0, 0, 0, 0);
        assertThat(problem(List.of(beans, salt), 2, 6)).isEmpty();
    }
}
