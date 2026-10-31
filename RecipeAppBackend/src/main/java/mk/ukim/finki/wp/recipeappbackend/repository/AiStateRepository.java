package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.projection.DailySetRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The AI feature's small shared state: the per-day generation counter ({@code ai_usage}) and the
 * daily meal set claims ({@code daily_meal_sets}). They need {@code ON CONFLICT ... RETURNING} and
 * conditional updates, so they are plain SQL rather than JPA entities. Every statement is one
 * round trip and none holds a connection while an AI call runs. SQL text is fixed; values are
 * bound as parameters.
 */
@Repository
public class AiStateRepository {

    private static final RowMapper<DailySetRow> DAILY_SET_ROW = (rs, i) -> new DailySetRow(
            rs.getObject("meal_date", LocalDate.class),
            rs.getString("status"),
            rs.getObject("breakfast_id", UUID.class),
            rs.getObject("lunch_id", UUID.class),
            rs.getObject("dinner_id", UUID.class),
            rs.getObject("attempted_at", OffsetDateTime.class));

    private static final String DAILY_SET_COLUMNS =
            "meal_date, status, breakfast_id, lunch_id, dinner_id, attempted_at";

    private final NamedParameterJdbcTemplate jdbc;

    public AiStateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes one generation from the user's count for {@code day} (UTC). Returns the new count, or
     * empty when {@code limit} is already reached. A single statement, so two racing requests can
     * never both take the last one.
     */
    public OptionalInt reserveGeneration(UUID userId, LocalDate day, int limit) {
        List<Integer> rows = jdbc.queryForList(
                "INSERT INTO ai_usage (user_id, usage_date, generations) VALUES (:user, :day, 1) "
                        + "ON CONFLICT (user_id, usage_date) DO UPDATE SET generations = ai_usage.generations + 1 "
                        + "WHERE ai_usage.generations < :limit RETURNING generations",
                new MapSqlParameterSource().addValue("user", userId).addValue("day", day).addValue("limit", limit),
                Integer.class);
        return rows.isEmpty() ? OptionalInt.empty() : OptionalInt.of(rows.get(0));
    }

    /** Gives one generation back (a failed generation is free). Never goes below zero. */
    public void refundGeneration(UUID userId, LocalDate day) {
        jdbc.update("UPDATE ai_usage SET generations = generations - 1 "
                        + "WHERE user_id = :user AND usage_date = :day AND generations > 0",
                new MapSqlParameterSource().addValue("user", userId).addValue("day", day));
    }

    public int generationsUsed(UUID userId, LocalDate day) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT generations FROM ai_usage WHERE user_id = :user AND usage_date = :day",
                new MapSqlParameterSource().addValue("user", userId).addValue("day", day), Integer.class);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /**
     * Tries to become the one caller that generates the user's set for {@code date}. Inserts a
     * {@code generating} row; if one exists, takes it over only when it is a crashed attempt
     * ({@code generating} for over 2 minutes) or a cooled-down failure ({@code failed} for over
     * 15 minutes). True means this caller now owns the generation; everyone else reads the row.
     */
    public boolean claimDailySet(UUID userId, LocalDate date) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("user", userId).addValue("date", date);
        int inserted = jdbc.update("INSERT INTO daily_meal_sets (user_id, meal_date, status) "
                + "VALUES (:user, :date, 'generating') ON CONFLICT (user_id, meal_date) DO NOTHING", params);
        if (inserted > 0) {
            return true;
        }
        return jdbc.update("UPDATE daily_meal_sets SET status = 'generating', attempted_at = now() "
                + "WHERE user_id = :user AND meal_date = :date AND ("
                + "(status = 'generating' AND attempted_at < now() - interval '2 minutes') "
                + "OR (status = 'failed' AND attempted_at < now() - interval '15 minutes'))", params) > 0;
    }

    public Optional<DailySetRow> findDailySet(UUID userId, LocalDate date) {
        return jdbc.query("SELECT " + DAILY_SET_COLUMNS + " FROM daily_meal_sets "
                                + "WHERE user_id = :user AND meal_date = :date",
                        new MapSqlParameterSource().addValue("user", userId).addValue("date", date), DAILY_SET_ROW)
                .stream().findFirst();
    }

    /** The user's newest {@code ready} set dated strictly before {@code date}, for the fallback. */
    public Optional<DailySetRow> latestReadyBefore(UUID userId, LocalDate date) {
        return jdbc.query("SELECT " + DAILY_SET_COLUMNS + " FROM daily_meal_sets "
                                + "WHERE user_id = :user AND status = 'ready' AND meal_date < :date "
                                + "ORDER BY meal_date DESC LIMIT 1",
                        new MapSqlParameterSource().addValue("user", userId).addValue("date", date), DAILY_SET_ROW)
                .stream().findFirst();
    }

    /**
     * Marks the set {@code ready} with its three recipes. Only a row still {@code generating}
     * changes, so a stale owner whose claim was taken over cannot overwrite the new result. True
     * when a row changed.
     */
    public boolean finishDailySet(UUID userId, LocalDate date, UUID breakfastId, UUID lunchId, UUID dinnerId) {
        return jdbc.update("UPDATE daily_meal_sets SET status = 'ready', breakfast_id = :b, lunch_id = :l, "
                        + "dinner_id = :d WHERE user_id = :user AND meal_date = :date AND status = 'generating'",
                new MapSqlParameterSource().addValue("user", userId).addValue("date", date)
                        .addValue("b", breakfastId).addValue("l", lunchId).addValue("d", dinnerId)) > 0;
    }

    /** Marks a {@code generating} set {@code failed} (and restarts the cooldown). True when a row changed. */
    public boolean failDailySet(UUID userId, LocalDate date) {
        return jdbc.update("UPDATE daily_meal_sets SET status = 'failed', attempted_at = now() "
                        + "WHERE user_id = :user AND meal_date = :date AND status = 'generating'",
                new MapSqlParameterSource().addValue("user", userId).addValue("date", date)) > 0;
    }

    /**
     * Cleanup steps 1 and 2, in this order (sets reference recipes): delete sets dated before
     * {@code cutoffDate} except each user's latest {@code ready} set; then delete unsaved recipes
     * created before {@code cutoffTime} that no remaining set references. Returns the two row
     * counts. The caller wraps this in one transaction.
     */
    public int[] cleanupOlderThan(LocalDate cutoffDate, OffsetDateTime cutoffTime) {
        int sets = jdbc.update("DELETE FROM daily_meal_sets s WHERE s.meal_date < :cutoffDate "
                        + "AND NOT (s.status = 'ready' AND s.meal_date = ("
                        + "SELECT max(r.meal_date) FROM daily_meal_sets r "
                        + "WHERE r.user_id = s.user_id AND r.status = 'ready'))",
                new MapSqlParameterSource().addValue("cutoffDate", cutoffDate));
        int recipes = jdbc.update("DELETE FROM generated_recipes g WHERE g.created_at < :cutoffTime "
                        + "AND g.saved_at IS NULL AND NOT EXISTS (SELECT 1 FROM daily_meal_sets s "
                        + "WHERE s.breakfast_id = g.id OR s.lunch_id = g.id OR s.dinner_id = g.id)",
                new MapSqlParameterSource().addValue("cutoffTime", cutoffTime));
        return new int[]{sets, recipes};
    }
}
