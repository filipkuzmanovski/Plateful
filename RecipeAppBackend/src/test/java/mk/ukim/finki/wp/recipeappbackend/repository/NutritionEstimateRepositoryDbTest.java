package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionTarget;
import mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionTotals;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.util.UUID;

import static mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionTarget.Kind.GENERATED;
import static mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionTarget.Kind.RECIPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs the nutrition estimate statements against the real Postgres. Opt-in: skipped unless
 * SUPABASE_DB_PASSWORD is set, and skipped while V13 is not applied (the backend applies a
 * migration on its next start). Flyway is disabled here so a test can never migrate the real
 * database. Real rows exist, so fixtures are dated 2099 to sort first and the {@code since} values
 * are 2098 to shut out real unsaved AI recipes. Every write stays in the rolled-back test transaction.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class NutritionEstimateRepositoryDbTest {

    private static final OffsetDateTime SINCE_2098 = OffsetDateTime.parse("2098-01-01T00:00:00Z");
    private static final OffsetDateTime Y2099_01 = OffsetDateTime.parse("2099-01-01T00:00:00Z");
    private static final OffsetDateTime Y2099_02 = OffsetDateTime.parse("2099-01-02T00:00:00Z");
    private static final OffsetDateTime Y2097 = OffsetDateTime.parse("2097-01-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    // Built by hand instead of @Import-ed: an @Import changes the Spring test-context cache key, and a
    // second cached context opens a second Hikari pool, which exhausts the Supabase session limit.
    private NutritionEstimateRepository repository;

    @BeforeEach
    void setUp() {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass('public.nutrition_estimates') IS NOT NULL",
                Boolean.class);
        Assumptions.assumeTrue(Boolean.TRUE.equals(exists), "V13 is not applied yet (restart the backend)");
        repository = new NutritionEstimateRepository(new NamedParameterJdbcTemplate(jdbc));
    }

    // users.id references auth.users(id), so a throwaway user needs a row in both.
    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.users (id) VALUES (?)", id);
        jdbc.update("INSERT INTO users (id, first_name, last_name, email) VALUES (?, 'Nutrition', 'Test', ?)",
                id, "nutrition-" + id + "@example.test");
        return id;
    }

    private UUID newRecipe(String source, String status, Integer servings, OffsetDateTime reviewedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recipes (id, user_id, source, title, servings, status, reviewed_at, created_at) "
                        + "VALUES (?, NULL, ?, 'Nutrition fixture', ?, ?, ?, ?)",
                id, source, servings, status, reviewedAt, reviewedAt);
        return id;
    }

    private UUID newUpload(OffsetDateTime at) {
        return newRecipe("user", "approved", 4, at);
    }

    private UUID newGenerated(OffsetDateTime createdAt, OffsetDateTime savedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO generated_recipes (id, user_id, kind, title, recipe, request, provider, model, "
                        + "novelty_score, created_at, saved_at) "
                        + "VALUES (?, ?, 'cook', 'Nutrition fixture', '{}'::jsonb, '{}'::jsonb, 'test', 'test', 0.1, ?, ?)",
                id, newUser(), createdAt, savedAt);
        return id;
    }

    private static NutritionTarget recipe(UUID id) {
        return new NutritionTarget(RECIPE, id);
    }

    @Test
    void next_prefersTheNewestEligibleOfEitherKind() {
        newUpload(Y2099_01);
        UUID ai = newGenerated(Y2099_02, null);

        assertThat(repository.next(SINCE_2098)).contains(new NutritionTarget(GENERATED, ai));
    }

    @Test
    void next_returnsAnApprovedUploadWithServings() {
        UUID upload = newUpload(Y2099_01);

        assertThat(repository.next(SINCE_2098)).contains(recipe(upload));
    }

    @ParameterizedTest
    @CsvSource(value = {"user,pending,4", "user,rejected,4", "user,approved,NULL",
            "user,approved,0", "spoonacular,approved,4"}, nullValues = "NULL")
    void next_skipsIneligibleUploads(String source, String status, Integer servings) {
        UUID fixture = newRecipe(source, status, servings, Y2099_01);

        assertThat(repository.next(SINCE_2098).map(NutritionTarget::id).filter(fixture::equals)).isEmpty();
    }

    @Test
    void next_skipsOldUnsavedAiRecipes() {
        UUID old = newGenerated(Y2097, null);

        assertThat(repository.next(SINCE_2098).map(NutritionTarget::id).filter(old::equals)).isEmpty();
    }

    @Test
    void next_includesOldSavedAiRecipes() {
        UUID saved = newGenerated(Y2097, Y2099_01);

        assertThat(repository.next(SINCE_2098).map(NutritionTarget::id)).contains(saved);
    }

    @Test
    void next_skipsAnythingWithARow() {
        UUID upload = newUpload(Y2099_01);
        repository.saveFailed(recipe(upload), "h");

        assertThat(repository.next(SINCE_2098).map(NutritionTarget::id).filter(upload::equals)).isEmpty();
    }

    @Test
    void saveReady_roundTrips() {
        UUID upload = newUpload(Y2099_01);

        repository.saveReady(recipe(upload), new NutritionTotals(900, 50, 30, 100), "[]", "h", "groq", "m");

        var row = repository.forRecipe(upload).orElseThrow();
        assertThat(row.status()).isEqualTo("ready");
        assertThat(row.calories()).isEqualByComparingTo("900");
        assertThat(row.proteinGrams()).isEqualByComparingTo("50");
        assertThat(row.fatGrams()).isEqualByComparingTo("30");
        assertThat(row.carbsGrams()).isEqualByComparingTo("100");
        assertThat(row.ingredientsHash()).isEqualTo("h");
    }

    @Test
    void saveReady_forAnAiRecipe_isReadByForGenerated() {
        UUID ai = newGenerated(Y2099_01, null);

        repository.saveReady(new NutritionTarget(GENERATED, ai), new NutritionTotals(400, 20, 10, 60),
                "[{\"line\":1}]", "g", "groq", "m");

        assertThat(repository.forGenerated(ai).orElseThrow().calories()).isEqualByComparingTo("400");
        assertThat(repository.forRecipe(ai)).isEmpty();
    }

    @Test
    void save_keepsTheFirstRowOnConflict() {
        UUID upload = newUpload(Y2099_01);

        repository.saveReady(recipe(upload), new NutritionTotals(900, 50, 30, 100), "[]", "h", "groq", "m");
        repository.saveFailed(recipe(upload), "other");

        var row = repository.forRecipe(upload).orElseThrow();
        assertThat(row.status()).isEqualTo("ready");
        assertThat(row.ingredientsHash()).isEqualTo("h");
    }

    @Test
    void deleteForRecipeIfHashDiffers_keepsTheSameHash_removesAnother_includingFailed() {
        UUID same = newUpload(Y2099_01);
        UUID changed = newUpload(Y2099_02);
        repository.saveReady(recipe(same), new NutritionTotals(1, 1, 1, 1), "[]", "h", "groq", "m");
        repository.saveFailed(recipe(changed), "old");

        repository.deleteForRecipeIfHashDiffers(same, "h");
        repository.deleteForRecipeIfHashDiffers(changed, "new");

        assertThat(repository.forRecipe(same)).isPresent();
        assertThat(repository.forRecipe(changed)).isEmpty();
    }

    @Test
    void createdSince_countsReadyAndFailed() {
        UUID a = newUpload(Y2099_01);
        UUID b = newUpload(Y2099_02);
        repository.saveReady(recipe(a), new NutritionTotals(1, 1, 1, 1), "[]", "h", "groq", "m");
        repository.saveFailed(recipe(b), "h");

        assertThat(repository.createdSince(OffsetDateTime.now().minusDays(1))).isGreaterThanOrEqualTo(2);
        assertThat(repository.createdSince(OffsetDateTime.now().plusDays(1))).isZero();
    }

    // A violation aborts the Postgres transaction, so each constraint gets its own test.
    @Test
    void exactlyOneOwner_isEnforced_whenBothAreSet() {
        UUID upload = newUpload(Y2099_01);
        UUID ai = newGenerated(Y2099_01, null);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO nutrition_estimates "
                + "(recipe_id, generated_recipe_id, status, ingredients_hash) VALUES (?, ?, 'failed', 'h')",
                upload, ai)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void exactlyOneOwner_isEnforced_whenNeitherIsSet() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO nutrition_estimates (status, ingredients_hash) "
                + "VALUES ('failed', 'h')")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingARecipe_removesItsEstimate() {
        UUID upload = newUpload(Y2099_01);
        repository.saveFailed(recipe(upload), "h");

        jdbc.update("DELETE FROM recipes WHERE id = ?", upload);

        assertThat(repository.forRecipe(upload)).isEmpty();
    }

    @Test
    void readyWithoutNumbers_isRejected() {
        UUID upload = newUpload(Y2099_01);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO nutrition_estimates (recipe_id, status, calories, "
                + "ingredients_hash) VALUES (?, 'ready', NULL, 'h')", upload))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
