package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import org.junit.jupiter.api.Test;

class DraftParserTest {

    private final DraftParser parser = new DraftParser(new CuisineCatalog());

    private static String ingredients(int n) {
        return IntStream.rangeClosed(1, n)
                .mapToObj(i -> "{\"name\":\"ingredient " + (char) ('a' + (i % 26)) + "\",\"amount\":\"1 cup\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String steps(int n) {
        return IntStream.rangeClosed(1, n)
                .mapToObj(i -> "\"Do step " + i + ".\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String json(String title, String servings, String prep, String cook, String cuisine,
                               String ingredients, String steps) {
        return "{\"title\":" + title + ",\"description\":\"A tasty dish.\",\"servings\":" + servings
                + ",\"prepMinutes\":" + prep + ",\"cookMinutes\":" + cook + ",\"cuisine\":" + cuisine
                + ",\"ingredients\":" + ingredients + ",\"steps\":" + steps + "}";
    }

    private static String valid() {
        return json("\"Tomato Pasta\"", "4", "10", "20", "\"italian\"", ingredients(5), steps(3));
    }

    private static String withTitle(String title) {
        return json("\"" + title + "\"", "4", "10", "20", "null", ingredients(5), steps(3));
    }

    private static String withSteps(String stepsJson) {
        return json("\"Tomato Pasta\"", "4", "10", "20", "null", ingredients(5), stepsJson);
    }

    private void assertProblem(DraftParser.Result r) {
        assertThat(r.draft()).isNull();
        assertThat(r.problem()).isNotNull();
        assertThat(r.problem().reason()).isNotBlank();
    }

    @Test
    void validDraft_parses() {
        DraftParser.Result r = parser.parse(valid());
        assertThat(r.problem()).isNull();
        RecipeDraft d = r.draft();
        assertThat(d.title()).isEqualTo("Tomato Pasta");
        assertThat(d.description()).isEqualTo("A tasty dish.");
        assertThat(d.servings()).isEqualTo(4);
        assertThat(d.prepMinutes()).isEqualTo(10);
        assertThat(d.cookMinutes()).isEqualTo(20);
        assertThat(d.cuisine()).isEqualTo("italian");
        assertThat(d.ingredients()).hasSize(5);
        assertThat(d.ingredients().get(0).amount()).isEqualTo("1 cup");
        assertThat(d.steps()).hasSize(3);
    }

    @Test
    void title_120Ok_121Fails() {
        assertThat(parser.parse(withTitle("a".repeat(120))).problem()).isNull();
        DraftParser.Result r = parser.parse(withTitle("a".repeat(121)));
        assertProblem(r);
        assertThat(r.problem().reason()).isEqualTo("The title was longer than 120 characters.");
    }

    @Test
    void ingredients_3And20Ok_2And21Fail() {
        for (int n : new int[] {3, 20}) {
            assertThat(parser.parse(json("\"T\"", "4", "10", "20", "null", ingredients(n), steps(3))).problem())
                    .as("%d ingredients", n).isNull();
        }
        for (int n : new int[] {2, 21}) {
            assertProblem(parser.parse(json("\"T\"", "4", "10", "20", "null", ingredients(n), steps(3))));
        }
    }

    @Test
    void steps_2And15Ok_1And16Fail() {
        for (int n : new int[] {2, 15}) {
            assertThat(parser.parse(withSteps(steps(n))).problem()).as("%d steps", n).isNull();
        }
        for (int n : new int[] {1, 16}) {
            assertProblem(parser.parse(withSteps(steps(n))));
        }
    }

    @Test
    void step_600Ok_601Fails() {
        assertThat(parser.parse(withSteps("[\"" + "a".repeat(600) + "\",\"Serve.\"]")).problem()).isNull();
        assertProblem(parser.parse(withSteps("[\"" + "a".repeat(601) + "\",\"Serve.\"]")));
    }

    @Test
    void servings_1And12Ok_0And13Fail() {
        for (String s : new String[] {"1", "12"}) {
            assertThat(parser.parse(json("\"T\"", s, "10", "20", "null", ingredients(4), steps(3))).problem())
                    .isNull();
        }
        for (String s : new String[] {"0", "13"}) {
            assertProblem(parser.parse(json("\"T\"", s, "10", "20", "null", ingredients(4), steps(3))));
        }
    }

    @Test
    void minutes_0And600Ok_601Fails() {
        assertThat(parser.parse(json("\"T\"", "4", "0", "600", "null", ingredients(4), steps(3))).problem())
                .isNull();
        assertProblem(parser.parse(json("\"T\"", "4", "601", "20", "null", ingredients(4), steps(3))));
        assertProblem(parser.parse(json("\"T\"", "4", "10", "601", "null", ingredients(4), steps(3))));
        assertProblem(parser.parse(json("\"T\"", "4", "-1", "20", "null", ingredients(4), steps(3))));
    }

    @Test
    void unknownCuisine_becomesNull() {
        DraftParser.Result r = parser.parse(json("\"T\"", "4", "10", "20", "\"klingon\"", ingredients(4), steps(3)));
        assertThat(r.problem()).isNull();
        assertThat(r.draft().cuisine()).isNull();
    }

    @Test
    void ingredientNameThatNormalisesToNothing_fails() {
        String bad = "[{\"name\":\"a\",\"amount\":\"1\"},{\"name\":\"b\",\"amount\":\"1\"},"
                + "{\"name\":\"123\",\"amount\":\"1\"}]";
        assertProblem(parser.parse(json("\"T\"", "4", "10", "20", "null", bad, steps(3))));
    }

    @Test
    void codeFence_isAProblem() {
        DraftParser.Result r = parser.parse("```json\n" + valid() + "\n```");
        assertProblem(r);
        assertThat(r.problem().reason()).isEqualTo("unreadable reply");
    }

    @Test
    void truncatedJson_isAProblem() {
        String v = valid();
        assertProblem(parser.parse(v.substring(0, v.length() / 2)));
    }

    @Test
    void proseNullBlankAndNonObjects_neverThrow() {
        for (String bad : new String[] {"Sure! Here is a recipe.", "", "   ", null, "[]", "42", "null", "\"text\""}) {
            assertProblem(parser.parse(bad));
        }
    }

    @Test
    void wrongTypesAndNulls_areProblems() {
        assertProblem(parser.parse(json("null", "4", "10", "20", "null", ingredients(4), steps(3))));
        assertProblem(parser.parse(json("\"T\"", "\"4\"", "10", "20", "null", ingredients(4), steps(3))));
        assertProblem(parser.parse(json("\"T\"", "2.5", "10", "20", "null", ingredients(4), steps(3))));
        assertProblem(parser.parse(json("\"T\"", "99999999999", "10", "20", "null", ingredients(4), steps(3))));
        assertProblem(parser.parse(json("\"T\"", "4", "10", "20", "null", "\"salt\"", steps(3))));
        assertProblem(parser.parse(json("\"T\"", "4", "10", "20", "null", ingredients(4), "null")));
        assertProblem(parser.parse(json("\"T\"", "4", "10", "20", "null", "[null,null,null]", steps(3))));
        assertProblem(parser.parse(withSteps("[null,\"Serve.\"]")));
        assertProblem(parser.parse(withSteps("[1,2]")));
        assertProblem(parser.parse("{}"));
    }

    @Test
    void parseMeals_oneMealMissing_onlyThatMealFails() {
        String body = "{\"breakfast\":" + valid() + ",\"dinner\":" + valid() + "}";
        Map<String, DraftParser.Result> results =
                parser.parseMeals(body, Set.of("breakfast", "lunch", "dinner"));
        assertThat(results).containsOnlyKeys("breakfast", "lunch", "dinner");
        assertThat(results.get("breakfast").problem()).isNull();
        assertThat(results.get("dinner").problem()).isNull();
        assertThat(results.get("lunch").draft()).isNull();
        assertThat(results.get("lunch").problem().reason()).contains("lunch");
    }

    @Test
    void parseMeals_unreadable_failsEveryMeal() {
        Map<String, DraftParser.Result> results =
                parser.parseMeals("```json {} ```", Set.of("breakfast", "lunch", "dinner"));
        assertThat(results).hasSize(3);
        results.values().forEach(r -> {
            assertThat(r.draft()).isNull();
            assertThat(r.problem().reason()).isEqualTo("unreadable reply");
        });
    }

    @Test
    void parseMeals_oneMealInvalid_onlyThatMealFails() {
        String bad = json("\"T\"", "0", "10", "20", "null", ingredients(4), steps(3));
        String body = "{\"breakfast\":" + valid() + ",\"lunch\":" + bad + ",\"dinner\":" + valid() + "}";
        Map<String, DraftParser.Result> results =
                parser.parseMeals(body, Set.of("breakfast", "lunch", "dinner"));
        assertThat(results.get("breakfast").problem()).isNull();
        assertThat(results.get("lunch").problem()).isNotNull();
        assertThat(results.get("dinner").problem()).isNull();
    }
}
