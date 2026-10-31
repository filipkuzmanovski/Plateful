package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.projection.DailySetRow;
import org.junit.jupiter.api.Assumptions;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs the AI state statements (usage reserve, daily set claim, cleanup) against the real Postgres.
 * Opt-in: skipped unless SUPABASE_DB_PASSWORD is set, and skipped while V9 is not applied (the
 * backend applies it on its next start; Flyway is disabled here so a test can never migrate the
 * real database). Every write, fixture users included, stays in the rolled-back test transaction.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class AiStateRepositoryDbTest {

    private static final LocalDate DAY = LocalDate.of(2031, 5, 10);

    @Autowired
    private JdbcTemplate jdbc;

    // Built by hand instead of @Import-ed: an @Import changes the Spring test-context cache key, and a
    // second cached context opens a second Hikari pool, which exhausts the Supabase session limit.
    private AiStateRepository repository;

    @BeforeEach
    void setUp() {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass('public.generated_recipes') IS NOT NULL",
                Boolean.class);
        Assumptions.assumeTrue(Boolean.TRUE.equals(exists), "V9 is not applied yet (restart the backend)");
        repository = new AiStateRepository(new NamedParameterJdbcTemplate(jdbc));
    }

    // users.id references auth.users(id), so a throwaway user needs a row in both.
    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.users (id) VALUES (?)", id);
        jdbc.update("INSERT INTO users (id, first_name, last_name, email) VALUES (?, 'Ai', 'State', ?)",
                id, "ai-state-" + id + "@example.test");
        return id;
    }

    private UUID newRecipe(UUID user, String kind, String title, int daysOld, boolean saved) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO generated_recipes (id, user_id, kind, title, recipe, request, provider, model, "
                        + "novelty_score, created_at, saved_at) "
                        + "VALUES (?, ?, ?, ?, '{}'::jsonb, '{}'::jsonb, 'test', 'test', 0.1, "
                        + "now() - make_interval(days => ?), CASE WHEN ? THEN now() ELSE NULL END)",
                id, user, kind, title, daysOld, saved);
        return id;
    }

    private void insertSet(UUID user, LocalDate date, String status, UUID b, UUID l, UUID d, int minutesAgo) {
        jdbc.update("INSERT INTO daily_meal_sets (user_id, meal_date, status, breakfast_id, lunch_id, dinner_id, "
                        + "attempted_at) VALUES (?, ?, ?, ?, ?, ?, now() - make_interval(mins => ?))",
                user, date, status, b, l, d, minutesAgo);
    }

    // now() is constant inside one transaction, so age a row by moving its attempted_at back.
    private void setState(UUID user, LocalDate date, String status, int minutesAgo) {
        jdbc.update("UPDATE daily_meal_sets SET status = ?, attempted_at = now() - make_interval(mins => ?) "
                + "WHERE user_id = ? AND meal_date = ?", status, minutesAgo, user, date);
    }

    private boolean recipeExists(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM generated_recipes WHERE id = ?", Integer.class, id) == 1;
    }

    private boolean setExists(UUID user, LocalDate date) {
        return jdbc.queryForObject("SELECT count(*) FROM daily_meal_sets WHERE user_id = ? AND meal_date = ?",
                Integer.class, user, date) == 1;
    }

    @Test
    void reserve_countsUpToLimitThenEmpty() {
        UUID u = newUser();

        assertThat(repository.reserveGeneration(u, DAY, 2)).isEqualTo(OptionalInt.of(1));
        assertThat(repository.reserveGeneration(u, DAY, 2)).isEqualTo(OptionalInt.of(2));
        assertThat(repository.reserveGeneration(u, DAY, 2)).isEmpty();
        assertThat(repository.generationsUsed(u, DAY)).isEqualTo(2);
        // A different day is a fresh count.
        assertThat(repository.reserveGeneration(u, DAY.plusDays(1), 2)).isEqualTo(OptionalInt.of(1));
    }

    @Test
    void refund_floorsAtZero() {
        UUID u = newUser();

        repository.refundGeneration(u, DAY);
        assertThat(repository.generationsUsed(u, DAY)).isZero();

        repository.reserveGeneration(u, DAY, 5);
        repository.refundGeneration(u, DAY);
        assertThat(repository.generationsUsed(u, DAY)).isZero();
        repository.refundGeneration(u, DAY);
        assertThat(repository.generationsUsed(u, DAY)).isZero();
    }

    @Test
    void claim_firstCallTrue_secondCallReturnsFalse() {
        UUID u = newUser();

        assertThat(repository.claimDailySet(u, DAY)).isTrue();
        assertThat(repository.claimDailySet(u, DAY)).isFalse();

        Optional<DailySetRow> row = repository.findDailySet(u, DAY);
        assertThat(row).isPresent();
        assertThat(row.get().status()).isEqualTo("generating");
        assertThat(row.get().date()).isEqualTo(DAY);
        assertThat(row.get().breakfastId()).isNull();
        assertThat(row.get().attemptedAt()).isNotNull();
    }

    @Test
    void claim_takesOverStaleGenerating() {
        UUID u = newUser();
        assertThat(repository.claimDailySet(u, DAY)).isTrue();

        setState(u, DAY, "generating", 1);
        assertThat(repository.claimDailySet(u, DAY)).isFalse();

        setState(u, DAY, "generating", 3);
        assertThat(repository.claimDailySet(u, DAY)).isTrue();
        // The take-over restarted the clock, so the next caller no longer takes it over.
        assertThat(repository.claimDailySet(u, DAY)).isFalse();
    }

    @Test
    void claim_failedWithinCooldown_false_afterCooldown_true() {
        UUID u = newUser();
        assertThat(repository.claimDailySet(u, DAY)).isTrue();
        repository.failDailySet(u, DAY);
        assertThat(repository.findDailySet(u, DAY).orElseThrow().status()).isEqualTo("failed");

        setState(u, DAY, "failed", 14);
        assertThat(repository.claimDailySet(u, DAY)).isFalse();

        setState(u, DAY, "failed", 16);
        assertThat(repository.claimDailySet(u, DAY)).isTrue();
        assertThat(repository.findDailySet(u, DAY).orElseThrow().status()).isEqualTo("generating");
    }

    @Test
    void claim_readySetIsNeverTakenOver() {
        UUID u = newUser();
        UUID b = newRecipe(u, "daily", "B", 0, false);
        UUID l = newRecipe(u, "daily", "L", 0, false);
        UUID d = newRecipe(u, "daily", "D", 0, false);
        assertThat(repository.claimDailySet(u, DAY)).isTrue();
        assertThat(repository.finishDailySet(u, DAY, b, l, d)).isTrue();
        setState(u, DAY, "ready", 600);

        assertThat(repository.claimDailySet(u, DAY)).isFalse();
        DailySetRow row = repository.findDailySet(u, DAY).orElseThrow();
        assertThat(row.status()).isEqualTo("ready");
        assertThat(row.breakfastId()).isEqualTo(b);
        assertThat(row.lunchId()).isEqualTo(l);
        assertThat(row.dinnerId()).isEqualTo(d);
    }

    @Test
    void findDailySet_missing_isEmpty() {
        assertThat(repository.findDailySet(newUser(), DAY)).isEmpty();
    }

    @Test
    void latestReadyBefore_skipsFailedAndSameDay() {
        UUID u = newUser();
        UUID b1 = newRecipe(u, "daily", "B1", 0, false);
        UUID l1 = newRecipe(u, "daily", "L1", 0, false);
        UUID d1 = newRecipe(u, "daily", "D1", 0, false);
        UUID b2 = newRecipe(u, "daily", "B2", 0, false);
        UUID l2 = newRecipe(u, "daily", "L2", 0, false);
        UUID d2 = newRecipe(u, "daily", "D2", 0, false);
        insertSet(u, DAY.minusDays(3), "ready", b1, l1, d1, 0);
        insertSet(u, DAY.minusDays(2), "ready", b2, l2, d2, 0);
        insertSet(u, DAY.minusDays(1), "failed", null, null, null, 0);
        insertSet(u, DAY, "ready", b2, l2, d2, 0);

        Optional<DailySetRow> found = repository.latestReadyBefore(u, DAY);

        assertThat(found).isPresent();
        assertThat(found.get().date()).isEqualTo(DAY.minusDays(2));
        assertThat(found.get().status()).isEqualTo("ready");
        assertThat(found.get().breakfastId()).isEqualTo(b2);
        assertThat(repository.latestReadyBefore(u, DAY.minusDays(3))).isEmpty();
        assertThat(repository.latestReadyBefore(newUser(), DAY)).isEmpty();
    }

    @Test
    void finishAndFail_onlyChangeAGeneratingRow() {
        UUID u = newUser();
        UUID b = newRecipe(u, "daily", "B", 0, false);
        UUID l = newRecipe(u, "daily", "L", 0, false);
        UUID d = newRecipe(u, "daily", "D", 0, false);
        UUID other = newRecipe(u, "daily", "X", 0, false);

        // No row at all.
        assertThat(repository.finishDailySet(u, DAY, b, l, d)).isFalse();
        assertThat(repository.failDailySet(u, DAY)).isFalse();

        // A ready row is not overwritten by a late finish or fail.
        assertThat(repository.claimDailySet(u, DAY)).isTrue();
        assertThat(repository.finishDailySet(u, DAY, b, l, d)).isTrue();
        assertThat(repository.finishDailySet(u, DAY, other, other, other)).isFalse();
        assertThat(repository.failDailySet(u, DAY)).isFalse();
        DailySetRow ready = repository.findDailySet(u, DAY).orElseThrow();
        assertThat(ready.status()).isEqualTo("ready");
        assertThat(ready.breakfastId()).isEqualTo(b);

        // A failed row is not turned ready by a late finish.
        LocalDate next = DAY.plusDays(1);
        assertThat(repository.claimDailySet(u, next)).isTrue();
        assertThat(repository.failDailySet(u, next)).isTrue();
        assertThat(repository.finishDailySet(u, next, b, l, d)).isFalse();
        DailySetRow failed = repository.findDailySet(u, next).orElseThrow();
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.breakfastId()).isNull();
    }

    @Test
    void cleanup_keepsLatestReadySetAndSavedRecipes() {
        UUID a = newUser();
        UUID b = newUser();
        LocalDate today = LocalDate.of(2031, 6, 1);
        // User A has two old ready sets: only the later one (the latest ready) survives, with its recipes.
        UUID aOld1 = newRecipe(a, "daily", "a-old-b", 60, false);
        UUID aOld2 = newRecipe(a, "daily", "a-old-l", 60, false);
        UUID aOld3 = newRecipe(a, "daily", "a-old-d", 60, false);
        UUID aKeep1 = newRecipe(a, "daily", "a-keep-b", 40, false);
        UUID aKeep2 = newRecipe(a, "daily", "a-keep-l", 40, false);
        UUID aKeep3 = newRecipe(a, "daily", "a-keep-d", 40, false);
        insertSet(a, today.minusDays(60), "ready", aOld1, aOld2, aOld3, 0);
        insertSet(a, today.minusDays(40), "ready", aKeep1, aKeep2, aKeep3, 0);
        // An old failed set goes; a recent generating set (not ready) stays because it is recent.
        insertSet(a, today.minusDays(45), "failed", null, null, null, 0);
        insertSet(a, today.minusDays(1), "generating", null, null, null, 0);
        // User B's latest ready set is OLDER than A's. "Latest ready" is per user, so it still survives;
        // B's earlier ready set goes.
        UUID bOld = newRecipe(b, "daily", "b-old", 80, false);
        UUID bKeep = newRecipe(b, "daily", "b-keep", 50, false);
        insertSet(b, today.minusDays(80), "ready", bOld, bOld, bOld, 0);
        insertSet(b, today.minusDays(50), "ready", bKeep, bKeep, bKeep, 0);
        // Loose recipes: an old unsaved cook recipe goes, an old saved one stays, a recent one stays.
        UUID oldCook = newRecipe(a, "cook", "old-cook", 50, false);
        UUID savedCook = newRecipe(a, "cook", "saved-cook", 50, true);
        UUID recentCook = newRecipe(a, "cook", "recent-cook", 2, false);

        int[] counts = repository.cleanupOlderThan(today.minusDays(30), OffsetDateTime.now().minusDays(30));

        assertThat(counts).hasSize(2);
        assertThat(counts[0]).isGreaterThanOrEqualTo(3);
        assertThat(counts[1]).isGreaterThanOrEqualTo(5);
        assertThat(setExists(a, today.minusDays(60))).isFalse();
        assertThat(setExists(a, today.minusDays(45))).isFalse();
        assertThat(setExists(a, today.minusDays(40))).isTrue();
        assertThat(setExists(a, today.minusDays(1))).isTrue();
        assertThat(setExists(b, today.minusDays(80))).isFalse();
        assertThat(setExists(b, today.minusDays(50))).isTrue();
        assertThat(recipeExists(aOld1)).isFalse();
        assertThat(recipeExists(aOld2)).isFalse();
        assertThat(recipeExists(aOld3)).isFalse();
        assertThat(recipeExists(bOld)).isFalse();
        assertThat(recipeExists(oldCook)).isFalse();
        assertThat(recipeExists(aKeep1)).isTrue();
        assertThat(recipeExists(aKeep2)).isTrue();
        assertThat(recipeExists(aKeep3)).isTrue();
        assertThat(recipeExists(bKeep)).isTrue();
        assertThat(recipeExists(savedCook)).isTrue();
        assertThat(recipeExists(recentCook)).isTrue();
    }
}
