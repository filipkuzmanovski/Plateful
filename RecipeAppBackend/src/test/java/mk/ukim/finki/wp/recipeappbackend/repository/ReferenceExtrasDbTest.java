package mk.ukim.finki.wp.recipeappbackend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.generation.ComparedRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.projection.Pairing;
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

/**
 * Real-Postgres tests for {@code randomForMeal}, {@code pairings} and {@code closestApproved}. Opt-in:
 * skipped unless SUPABASE_DB_PASSWORD is set. Fixtures are written inside the rolled-back test
 * transaction: reference rows with negative ids and a made-up meal key, ingredient and recipe names with a
 * "zz" prefix, so the real dataset (50,000 rows) cannot disturb the assertions. Flyway is disabled so a
 * test can never migrate the real database.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class ReferenceExtrasDbTest {

    @Autowired
    private JdbcTemplate jdbc;

    // Built by hand, not @Import-ed: a second cached Spring context would open a second Hikari pool.
    private ReferenceDataRepository reference;
    private PlatefulComparisonRepository plateful;

    private void insertReference(long id, String names, String meals, String allergens,
                                 boolean vegetarian, boolean vegan, boolean glutenFree, boolean dairyFree) {
        jdbc.update("INSERT INTO reference_recipes (id, title, ingredient_lines, ingredient_names, directions, "
                        + "meal_types, allergens, vegetarian, vegan, gluten_free, dairy_free) "
                        + "VALUES (?, ?, ARRAY['1 line'], CAST(? AS text[]), ARRAY['Cook.'], "
                        + "CAST(? AS text[]), CAST(? AS text[]), ?, ?, ?, ?)",
                id, "Fixture " + id, names, meals, allergens, vegetarian, vegan, glutenFree, dairyFree);
    }

    private void insertPlateful(String title, String status, String... ingredientNames) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recipes (id, title, status) VALUES (?, ?, ?)", id, title, status);
        for (int i = 0; i < ingredientNames.length; i++) {
            jdbc.update("INSERT INTO recipe_ingredients (recipe_id, ingredient_name, original_text, sort_order) "
                    + "VALUES (?, ?, ?, ?)", id, ingredientNames[i], "1 " + ingredientNames[i], i);
        }
    }

    @BeforeEach
    void setUp() {
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
        reference = new ReferenceDataRepository(named);
        plateful = new PlatefulComparisonRepository(named);
    }

    private static List<Long> fixtureIds(List<ReferenceRecipe> recipes) {
        return recipes.stream().map(ReferenceRecipe::id).filter(id -> id < 0).toList();
    }

    // ---- randomForMeal ----

    private void insertRandomFixtures() {
        insertReference(-1, "{zzegg,zzflour,zzmilk}", "{zzmeal}", "{eggs,gluten,milk}", false, false, false, false);
        insertReference(-2, "{zzegg,zzspinach}", "{zzmeal}", "{eggs}", true, false, true, true);
        insertReference(-3, "{\"zzshiitake zzmushroom\",zzegg}", "{zzmeal}", "{eggs}", false, false, true, true);
        insertReference(-4, "{zzchicken,zzrice}", "{zzmeal}", "{}", false, false, true, true);
        insertReference(-5, "{zzoat,zzbanana}", "{zzother}", "{}", true, true, false, true);
    }

    @Test
    void randomForMeal_respectsAllergenDietDislikeAndMeal() {
        insertRandomFixtures();

        // Meal only: the "zzmeal" fixtures, never the other meal; overlap is 0.
        List<ReferenceRecipe> all = reference.randomForMeal("zzmeal", Set.of(), Set.of(), Set.of(), 100);
        assertThat(fixtureIds(all)).containsExactlyInAnyOrder(-1L, -2L, -3L, -4L);
        assertThat(all).allSatisfy(r -> assertThat(r.overlap()).isZero());
        ReferenceRecipe two = all.stream().filter(r -> r.id() == -2).findFirst().orElseThrow();
        assertThat(two.title()).isEqualTo("Fixture -2");
        assertThat(two.ingredientLines()).containsExactly("1 line");
        assertThat(two.ingredientNames()).containsExactly("zzegg", "zzspinach");
        assertThat(two.directions()).containsExactly("Cook.");
        assertThat(two.mealTypes()).containsExactly("zzmeal");

        // Allergens.
        assertThat(fixtureIds(reference.randomForMeal("zzmeal", Set.of("milk"), Set.of(), Set.of(), 100)))
                .containsExactlyInAnyOrder(-2L, -3L, -4L);
        assertThat(fixtureIds(reference.randomForMeal("zzmeal", Set.of("milk", "eggs"), Set.of(), Set.of(), 100)))
                .containsExactly(-4L);

        // Diets are combined with AND (-2 is vegetarian and gluten free; -3 is neither vegetarian).
        assertThat(fixtureIds(reference.randomForMeal("zzmeal", Set.of(), Set.of("vegetarian"), Set.of(), 100)))
                .containsExactly(-2L);
        assertThat(fixtureIds(reference.randomForMeal("zzmeal", Set.of(), Set.of("vegetarian", "gluten_free"),
                Set.of(), 100))).containsExactly(-2L);

        // Dislikes: whole word, normalised first, never inside another word.
        assertThat(fixtureIds(reference.randomForMeal("zzmeal", Set.of(), Set.of(), Set.of("ZZMushrooms"), 100)))
                .containsExactlyInAnyOrder(-1L, -2L, -4L);
        assertThat(fixtureIds(reference.randomForMeal("zzmeal", Set.of(), Set.of(), Set.of("zzmush"), 100)))
                .containsExactlyInAnyOrder(-1L, -2L, -3L, -4L);
    }

    @Test
    void randomForMeal_limitsAndRejectsUnknownDiet() {
        insertRandomFixtures();

        assertThat(reference.randomForMeal("zzmeal", Set.of(), Set.of(), Set.of(), 2)).hasSize(2);
        assertThatThrownBy(() -> reference.randomForMeal("zzmeal", Set.of(), Set.of("paleo"), Set.of(), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void randomForMeal_onTheRealDatasetReturnsRecipesOfThatMeal() {
        List<ReferenceRecipe> result = reference.randomForMeal("dinner", Set.of("peanuts"), Set.of(), Set.of(), 5);

        assumeTrue(!result.isEmpty(), "reference dataset not loaded");
        assertThat(result).hasSizeLessThanOrEqualTo(5);
        assertThat(result).allSatisfy(r -> assertThat(r.mealTypes()).contains("dinner"));
    }

    // ---- pairings ----

    @Test
    void pairings_orderedByCount_limited_withPercent() {
        jdbc.update("INSERT INTO ingredient_stats (name, recipe_count) VALUES ('zzsalmon', 200)");
        jdbc.update("INSERT INTO ingredient_pairings (ingredient, partner, together_count) VALUES "
                + "('zzsalmon', 'zzlemon', 100), ('zzsalmon', 'zzdill', 150), ('zzsalmon', 'zzrice', 30), "
                + "('zzsalmon', 'zzkale', 1)");
        // Another ingredient's pairings are never mixed in.
        jdbc.update("INSERT INTO ingredient_stats (name, recipe_count) VALUES ('zztuna', 10)");
        jdbc.update("INSERT INTO ingredient_pairings (ingredient, partner, together_count) VALUES "
                + "('zztuna', 'zzlemon', 9)");

        List<Pairing> top3 = reference.pairings("zzsalmon", 3);

        assertThat(top3).containsExactly(new Pairing("zzdill", 75), new Pairing("zzlemon", 50),
                new Pairing("zzrice", 15));
        assertThat(reference.pairings("zzsalmon", 1)).containsExactly(new Pairing("zzdill", 75));
        // The name is normalised first ("ZZSalmons" -> "zzsalmon"); unknown or empty names give nothing.
        assertThat(reference.pairings("ZZSalmons", 1)).containsExactly(new Pairing("zzdill", 75));
        assertThat(reference.pairings("zzunknown", 5)).isEmpty();
        assertThat(reference.pairings("!!!", 5)).isEmpty();
    }

    // ---- closestApproved ----

    @Test
    void closestApproved_skipsPendingAndRejected() {
        insertPlateful("ZZ approved best", "approved", "zzegg", "ZZTomatoes", "zzsalt");
        insertPlateful("ZZ approved other", "approved", "zzegg", "zzbread");
        insertPlateful("ZZ pending", "pending", "zzegg", "zztomato");
        insertPlateful("ZZ rejected", "rejected", "zzegg", "zztomato");

        List<ComparedRecipe> result = plateful.closestApproved(Set.of("zzegg", "zztomato"), 20);

        assertThat(result).containsExactly(
                new ComparedRecipe("ZZ approved best", Set.of("zzegg", "zztomato", "zzsalt")),
                new ComparedRecipe("ZZ approved other", Set.of("zzegg", "zzbread")));
    }

    @Test
    void closestApproved_ranksByOverlapLimitsAndHandlesEmptyInput() {
        insertPlateful("ZZ one", "approved", "zzaa", "zzx1");
        insertPlateful("ZZ two", "approved", "zzaa", "zzbb", "zzx2");
        insertPlateful("ZZ three", "approved", "zzaa", "zzbb", "zzcc");

        assertThat(plateful.closestApproved(Set.of("zzaa", "zzbb", "zzcc"), 2))
                .extracting(ComparedRecipe::title).containsExactly("ZZ three", "ZZ two");
        assertThat(plateful.closestApproved(Set.of(), 5)).isEmpty();
        assertThat(plateful.closestApproved(Set.of("!!!"), 5)).isEmpty();
    }
}
