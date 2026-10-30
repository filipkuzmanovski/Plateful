package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.projection.ReferenceQuery;
import mk.ukim.finki.wp.recipeappbackend.model.projection.ReferenceRecipe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs the real reference query against the real Postgres, because the array binding and the
 * array operators are exactly what a fake repository cannot prove. Opt-in: skipped unless
 * SUPABASE_DB_PASSWORD is set. Fixture rows (negative ids) are inserted inside the test
 * transaction, which @DataJpaTest rolls back; results are narrowed to the fixture ids so a later
 * real import cannot disturb the assertions. Flyway is disabled so a test can never migrate the
 * real database.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class ReferenceRecipeQueryDbTest {

    @Autowired
    private JdbcTemplate jdbc;

    // Built by hand instead of @Import-ed: an @Import changes the Spring test-context cache key, and a
    // second cached context opens a second Hikari pool, which exhausts the Supabase session-mode limit.
    private ReferenceDataRepository repository;

    @BeforeEach
    void repository() {
        repository = new ReferenceDataRepository(new NamedParameterJdbcTemplate(jdbc));
    }

    private void insert(long id, String names, String meals, String allergens,
                        boolean vegetarian, boolean vegan, boolean glutenFree, boolean dairyFree) {
        jdbc.update("INSERT INTO reference_recipes (id, title, ingredient_lines, ingredient_names, directions, "
                        + "meal_types, allergens, vegetarian, vegan, gluten_free, dairy_free) "
                        + "VALUES (?, ?, ARRAY['1 line'], CAST(? AS text[]), ARRAY['Cook.'], "
                        + "CAST(? AS text[]), CAST(? AS text[]), ?, ?, ?, ?)",
                id, "Fixture " + id, names, meals, allergens, vegetarian, vegan, glutenFree, dairyFree);
    }

    @BeforeEach
    void fixtures() {
        insert(-1, "{egg,flour,milk}", "{breakfast}", "{eggs,gluten,milk}", false, false, false, false);
        insert(-2, "{egg,spinach}", "{breakfast}", "{eggs}", true, false, true, true);
        insert(-3, "{\"shiitake mushroom\",egg,rice}", "{dinner}", "{eggs}", false, false, true, true);
        insert(-4, "{chicken,rice}", "{dinner}", "{}", false, false, true, true);
        insert(-5, "{oat,banana,honey}", "{breakfast}", "{}", true, false, false, true);
    }

    private static ReferenceQuery query(Set<String> wanted, String meal, Set<String> allergens,
                                        Set<String> diets, Set<String> dislikes, int limit) {
        return new ReferenceQuery(wanted, meal, allergens, diets, dislikes, limit);
    }

    private static ReferenceQuery wanting(String... wanted) {
        return query(Set.of(wanted), null, Set.of(), Set.of(), Set.of(), 50);
    }

    private static List<Long> fixtureIds(List<ReferenceRecipe> recipes) {
        return recipes.stream().map(ReferenceRecipe::id).filter(id -> id < 0).toList();
    }

    @Test
    void ranksByOverlapAndFillsTheRecipe() {
        List<ReferenceRecipe> result = repository.findClosest(wanting("egg", "spinach"));

        assertThat(fixtureIds(result)).startsWith(-2L);
        ReferenceRecipe best = result.stream().filter(r -> r.id() == -2).findFirst().orElseThrow();
        assertThat(best.overlap()).isEqualTo(2);
        assertThat(best.title()).isEqualTo("Fixture -2");
        assertThat(best.ingredientLines()).containsExactly("1 line");
        assertThat(best.ingredientNames()).containsExactly("egg", "spinach");
        assertThat(best.directions()).containsExactly("Cook.");
        assertThat(best.mealTypes()).containsExactly("breakfast");
        assertThat(result.stream().filter(r -> r.id() == -1).findFirst().orElseThrow().overlap()).isEqualTo(1);
    }

    @Test
    void shorterRecipeWinsATieOnOverlapThenLowerIdFirst() {
        // All three overlap 1 on egg; -2 has 2 names, -1 and -3 have 3 (ordered by id: -3 before -1).
        assertThat(fixtureIds(repository.findClosest(wanting("egg")))).containsExactly(-2L, -3L, -1L);
    }

    @Test
    void mealTypeRestrictsToThatMeal() {
        List<ReferenceRecipe> result = repository.findClosest(
                query(Set.of("egg", "rice"), "dinner", Set.of(), Set.of(), Set.of(), 50));

        assertThat(fixtureIds(result)).containsExactly(-3L, -4L);
    }

    @Test
    void allergensExcludeTheRecipesThatContainThem() {
        List<ReferenceRecipe> result = repository.findClosest(
                query(Set.of("egg"), null, Set.of("milk"), Set.of(), Set.of(), 50));

        assertThat(fixtureIds(result)).doesNotContain(-1L).contains(-2L, -3L);
    }

    @Test
    void severalAllergensAreAllExcluded() {
        List<ReferenceRecipe> result = repository.findClosest(
                query(Set.of("egg", "rice"), null, Set.of("milk", "eggs"), Set.of(), Set.of(), 50));

        assertThat(fixtureIds(result)).containsExactly(-4L);
    }

    @Test
    void veganDietWithABananaOnlyRecipeGivesNothing() {
        List<ReferenceRecipe> result = repository.findClosest(
                query(Set.of("banana"), null, Set.of(), Set.of("vegan"), Set.of(), 50));

        assertThat(fixtureIds(result)).isEmpty();
    }

    @Test
    void dietsAreCombinedWithAnd() {
        List<ReferenceRecipe> vegetarian = repository.findClosest(
                query(Set.of("egg", "banana"), null, Set.of(), Set.of("vegetarian"), Set.of(), 50));
        List<ReferenceRecipe> vegetarianAndGlutenFree = repository.findClosest(
                query(Set.of("egg", "banana"), null, Set.of(), Set.of("vegetarian", "gluten_free"), Set.of(), 50));

        assertThat(fixtureIds(vegetarian)).containsExactlyInAnyOrder(-2L, -5L);
        assertThat(fixtureIds(vegetarianAndGlutenFree)).containsExactly(-2L);
    }

    @Test
    void dislikedWordMatchesWholeWordsInsideNames() {
        List<ReferenceRecipe> result = repository.findClosest(
                query(Set.of("egg"), null, Set.of(), Set.of(), Set.of("mushroom"), 50));

        assertThat(fixtureIds(result)).doesNotContain(-3L).contains(-1L, -2L);
    }

    @Test
    void dislikedWordDoesNotMatchInsideAnotherWord() {
        List<ReferenceRecipe> result = repository.findClosest(
                query(Set.of("egg"), null, Set.of(), Set.of(), Set.of("mush", "room"), 50));

        assertThat(fixtureIds(result)).contains(-1L, -2L, -3L);
    }

    @Test
    void emptyWantedReturnsNothing() {
        assertThat(repository.findClosest(wanting())).isEmpty();
    }

    @Test
    void limitIsRespected() {
        assertThat(repository.findClosest(
                query(Set.of("egg"), null, Set.of(), Set.of(), Set.of(), 2))).hasSize(2);
    }

    @Test
    void unknownDietKeyIsRejected() {
        assertThatThrownBy(() -> repository.findClosest(
                query(Set.of("egg"), null, Set.of(), Set.of("paleo"), Set.of(), 5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
