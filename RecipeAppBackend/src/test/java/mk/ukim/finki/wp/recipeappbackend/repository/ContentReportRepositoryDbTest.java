package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.ReportReason;
import mk.ukim.finki.wp.recipeappbackend.model.entities.ContentReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Checks content_reports (V15) and its repository against the real Postgres: the one-of-two check, the two
 * unique pairs, the reason check, the cascades from recipe, comment and reporter, and the counting queries.
 * Opt-in: skipped unless SUPABASE_DB_PASSWORD is set, and skipped until V15 is applied (the backend applies
 * it on its next start; Flyway is disabled here so a test can never migrate the real database). Every write
 * stays in the rolled-back test transaction. A constraint violation aborts the Postgres transaction, so each
 * test that expects one makes it its last statement.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class ContentReportRepositoryDbTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ContentReportRepository reports;

    private UUID authorId;
    private UUID reporterId;
    private UUID recipeId;
    private UUID commentId;

    @BeforeEach
    void seed() {
        Integer checks = jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = to_regclass('public.content_reports') AND contype IN ('c', 'u')
                """, Integer.class);
        assumeTrue(checks != null && checks >= 5, "V15 is not applied yet (restart the backend)");

        authorId = newUser("Author");
        reporterId = newUser("Reporter");
        recipeId = UUID.randomUUID();
        jdbc.update("INSERT INTO recipes (id, user_id, title, status, ever_approved) VALUES (?, ?, 'Report fixture', "
                + "'approved', true)", recipeId, authorId);
        commentId = UUID.randomUUID();
        jdbc.update("INSERT INTO recipe_comments (id, recipe_id, user_id, body) VALUES (?, ?, ?, 'Fixture comment')",
                commentId, recipeId, authorId);
    }

    // users.id references auth.users(id), so a throwaway user needs a row in both.
    private UUID newUser(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.users (id) VALUES (?)", id);
        jdbc.update("INSERT INTO users (id, first_name, last_name, email) VALUES (?, ?, 'Reports', ?)",
                id, name, name + "-" + id + "@example.test");
        return id;
    }

    private int insert(UUID reporter, UUID recipe, UUID comment, String reason, String state) {
        return jdbc.update("INSERT INTO content_reports (reporter_id, recipe_id, comment_id, reason, state) "
                + "VALUES (?, ?, ?, ?, ?)", reporter, recipe, comment, reason, state);
    }

    private int countFor(String column, UUID id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM content_reports WHERE " + column + " = ?",
                Integer.class, id);
        return n == null ? 0 : n;
    }

    // ---- the entity mapping ----

    @Test
    void entity_roundTripsThroughTheTable() {
        ContentReport saved = reports.saveAndFlush(ContentReport.builder()
                .reporterId(reporterId).recipeId(recipeId).reason(ReportReason.NOT_FOOD).note("A car").build());

        assertThat(jdbc.queryForObject("SELECT reason || '/' || state || '/' || note FROM content_reports WHERE id = ?",
                String.class, saved.getId())).isEqualTo("not_food/open/A car");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    // ---- constraints ----

    @Test
    void bothIds_isRefused() {
        assertThatThrownBy(() -> insert(reporterId, recipeId, commentId, "spam", "open"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("content_reports_check");
    }

    @Test
    void neitherId_isRefused() {
        assertThatThrownBy(() -> insert(reporterId, null, null, "spam", "open"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("content_reports_check");
    }

    @Test
    void unknownReason_isRefused() {
        assertThatThrownBy(() -> insert(reporterId, recipeId, null, "rude", "open"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("content_reports_reason_check");
    }

    @Test
    void eachReason_isAccepted() {
        for (ReportReason reason : ReportReason.values()) {
            assertThat(insert(newUser("R" + reason.ordinal()), recipeId, null, reason.text(), "open")).isEqualTo(1);
        }
    }

    @Test
    void sameReporterAndRecipe_isRefusedEvenWhenTheFirstWasDismissed() {
        insert(reporterId, recipeId, null, "spam", "dismissed");

        // ReportService maps exactly this constraint name to already_reported
        assertThatThrownBy(() -> insert(reporterId, recipeId, null, "other", "open"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("content_reports_reporter_id_recipe_id_key");
    }

    @Test
    void sameReporterAndComment_isRefused() {
        insert(reporterId, null, commentId, "spam", "open");

        assertThatThrownBy(() -> insert(reporterId, null, commentId, "offensive", "open"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("content_reports_reporter_id_comment_id_key");
    }

    @Test
    void oneReporter_canReportARecipeAndItsComment() {
        assertThat(insert(reporterId, recipeId, null, "spam", "open")).isEqualTo(1);
        assertThat(insert(reporterId, null, commentId, "spam", "open")).isEqualTo(1);
    }

    // ---- cascades ----

    @Test
    void deletingTheRecipe_deletesItsReportsAndItsCommentsReports() {
        insert(reporterId, recipeId, null, "spam", "open");
        insert(reporterId, null, commentId, "spam", "open");

        jdbc.update("DELETE FROM recipes WHERE id = ?", recipeId);

        assertThat(countFor("recipe_id", recipeId)).isZero();
        assertThat(countFor("comment_id", commentId)).isZero();
    }

    @Test
    void deletingTheComment_deletesItsReports() {
        insert(reporterId, null, commentId, "spam", "open");

        jdbc.update("DELETE FROM recipe_comments WHERE id = ?", commentId);

        assertThat(countFor("comment_id", commentId)).isZero();
    }

    @Test
    void deletingTheReporter_deletesTheirReports() {
        insert(reporterId, recipeId, null, "spam", "open");
        insert(reporterId, null, commentId, "spam", "open");

        jdbc.update("DELETE FROM users WHERE id = ?", reporterId);

        assertThat(countFor("reporter_id", reporterId)).isZero();
    }

    // ---- repository queries ----

    @Test
    void openDistinctReporters_countOpenReportsOnly() {
        insert(newUser("A"), recipeId, null, "spam", "open");
        insert(newUser("B"), recipeId, null, "spam", "open");
        insert(newUser("C"), recipeId, null, "spam", "dismissed");
        insert(newUser("D"), null, commentId, "spam", "open");
        insert(newUser("E"), null, commentId, "spam", "dismissed");

        assertThat(reports.countOpenDistinctReportersForRecipe(recipeId)).isEqualTo(2);
        assertThat(reports.countOpenDistinctReportersForComment(commentId)).isEqualTo(1);
    }

    @Test
    void dailyCount_countsEveryStateSinceTheCutoff() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID otherRecipe = UUID.randomUUID();
        jdbc.update("INSERT INTO recipes (id, user_id, title, status) VALUES (?, ?, 'Old fixture', 'approved')",
                otherRecipe, authorId);
        insert(reporterId, recipeId, null, "spam", "dismissed");
        insert(reporterId, null, commentId, "spam", "open");
        jdbc.update("INSERT INTO content_reports (reporter_id, recipe_id, reason, created_at) VALUES (?, ?, 'spam', ?)",
                reporterId, otherRecipe, now.minusHours(25));

        assertThat(reports.countByReporterIdAndCreatedAtAfter(reporterId, now.minusHours(24))).isEqualTo(2);
    }

    @Test
    void existsAndReportedCommentIds_seeEveryState() {
        UUID otherComment = UUID.randomUUID();
        jdbc.update("INSERT INTO recipe_comments (id, recipe_id, user_id, body) VALUES (?, ?, ?, 'Other')",
                otherComment, recipeId, authorId);
        insert(reporterId, recipeId, null, "spam", "dismissed");
        insert(reporterId, null, commentId, "spam", "dismissed");

        assertThat(reports.existsByReporterIdAndRecipeId(reporterId, recipeId)).isTrue();
        assertThat(reports.existsByReporterIdAndCommentId(reporterId, commentId)).isTrue();
        assertThat(reports.existsByReporterIdAndCommentId(reporterId, otherComment)).isFalse();
        assertThat(reports.existsByReporterIdAndRecipeId(authorId, recipeId)).isFalse();
        assertThat(reports.findReportedCommentIds(reporterId, List.of(commentId, otherComment)))
                .containsExactly(commentId);
    }
}
