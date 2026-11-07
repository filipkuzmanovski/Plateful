package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.ReportException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.ReportReason;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.ReportCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.MyReportsDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.ContentReport;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.repository.ContentReportRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Spec 3.3: the reporting rules, their order, the row lock and hiding at the third distinct reporter. */
class ReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final UUID authorId = UUID.randomUUID();
    private final UUID commenterId = UUID.randomUUID();
    private final UUID reporterId = UUID.randomUUID();

    private ContentReportRepository reportRepository;
    private RecipeRepository recipeRepository;
    private RecipeCommentRepository commentRepository;
    private ReportService service;

    private Recipe recipe;
    private RecipeComment comment;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ContentReportRepository.class);
        recipeRepository = mock(RecipeRepository.class);
        commentRepository = mock(RecipeCommentRepository.class);
        service = new ReportService(reportRepository, recipeRepository, commentRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        recipe = recipe(RecipeStatus.APPROVED, null);
        comment = comment(recipe, null);
        when(reportRepository.saveAndFlush(any(ContentReport.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Recipe recipe(RecipeStatus status, OffsetDateTime hiddenAt) {
        Recipe r = Recipe.builder()
                .id(UUID.randomUUID())
                .title("Soup")
                .status(status)
                .everApproved(status == RecipeStatus.APPROVED)
                .approvedTitle("Soup")
                .hiddenAt(hiddenAt)
                .user(User.builder().id(authorId).build())
                .build();
        when(recipeRepository.findForReviewById(r.getId())).thenReturn(Optional.of(r));
        when(recipeRepository.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    private RecipeComment comment(Recipe on, OffsetDateTime hiddenAt) {
        RecipeComment c = RecipeComment.builder()
                .id(UUID.randomUUID())
                .recipe(on)
                .user(User.builder().id(commenterId).build())
                .body("Too salty")
                .hiddenAt(hiddenAt)
                .build();
        when(commentRepository.findRecipeIdById(c.getId())).thenReturn(Optional.of(on.getId()));
        when(commentRepository.findForUpdateById(c.getId())).thenReturn(Optional.of(c));
        when(commentRepository.findById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private static ReportCreateDto recipeReport(UUID recipeId) {
        return new ReportCreateDto(recipeId, null, "spam", null);
    }

    private static ReportCreateDto commentReport(UUID commentId) {
        return new ReportCreateDto(null, commentId, "offensive", null);
    }

    private static void assertReportError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isExactlyInstanceOf(ReportException.class);
        ReportException ex = (ReportException) thrown;
        assertThat(ex.getStatus()).isEqualTo(status);
        assertThat(ex.getCode()).isEqualTo(code);
    }

    private ContentReport savedReport() {
        ArgumentCaptor<ContentReport> captor = ArgumentCaptor.forClass(ContentReport.class);
        verify(reportRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    // ---- saving ----

    @Test
    void report_recipe_savesOpenReport() {
        service.report(reporterId, new ReportCreateDto(recipe.getId(), null, "not_food", "  Not a recipe  "));

        ContentReport saved = savedReport();
        assertThat(saved.getReporterId()).isEqualTo(reporterId);
        assertThat(saved.getRecipeId()).isEqualTo(recipe.getId());
        assertThat(saved.getCommentId()).isNull();
        assertThat(saved.getReason()).isEqualTo(ReportReason.NOT_FOOD);
        assertThat(saved.getNote()).isEqualTo("Not a recipe");
        assertThat(saved.getState()).isEqualTo(ContentReport.OPEN);
    }

    @Test
    void report_comment_savesOpenReport() {
        service.report(reporterId, commentReport(comment.getId()));

        ContentReport saved = savedReport();
        assertThat(saved.getCommentId()).isEqualTo(comment.getId());
        assertThat(saved.getRecipeId()).isNull();
        assertThat(saved.getReason()).isEqualTo(ReportReason.OFFENSIVE);
        assertThat(saved.getState()).isEqualTo(ContentReport.OPEN);
    }

    @Test
    void report_eachOfTheFiveReasons_isAccepted() {
        for (String reason : List.of("spam", "offensive", "unsafe", "not_food", "other")) {
            Recipe r = recipe(RecipeStatus.APPROVED, null);
            service.report(reporterId, new ReportCreateDto(r.getId(), null, reason, null));
        }
        verify(reportRepository, times(5)).saveAndFlush(any());
    }

    @Test
    void report_blankNote_storedAsNull() {
        service.report(reporterId, new ReportCreateDto(recipe.getId(), null, "spam", "   "));

        assertThat(savedReport().getNote()).isNull();
    }

    @Test
    void report_note500Chars_isAccepted() {
        service.report(reporterId, new ReportCreateDto(recipe.getId(), null, "other", "x".repeat(500)));

        assertThat(savedReport().getNote()).hasSize(500);
    }

    // ---- invalid_report (400), checked before anything is read ----

    @Test
    void report_bothIdsOrNeither_isInvalidReport400() {
        Throwable both = catchThrowable(() ->
                service.report(reporterId, new ReportCreateDto(recipe.getId(), comment.getId(), "spam", null)));
        Throwable neither = catchThrowable(() ->
                service.report(reporterId, new ReportCreateDto(null, null, "spam", null)));

        assertReportError(both, HttpStatus.BAD_REQUEST, "invalid_report");
        assertReportError(neither, HttpStatus.BAD_REQUEST, "invalid_report");
        verifyNoInteractions(reportRepository, recipeRepository, commentRepository);
    }

    @Test
    void report_unknownReason_isInvalidReport400() {
        for (String reason : new String[] {"rude", "SPAM", "", null}) {
            Throwable thrown = catchThrowable(() ->
                    service.report(reporterId, new ReportCreateDto(recipe.getId(), null, reason, null)));
            assertReportError(thrown, HttpStatus.BAD_REQUEST, "invalid_report");
        }
        verifyNoInteractions(reportRepository, recipeRepository, commentRepository);
    }

    @Test
    void report_note501Chars_isInvalidReport400() {
        Throwable thrown = catchThrowable(() ->
                service.report(reporterId, new ReportCreateDto(recipe.getId(), null, "spam", "x".repeat(501))));

        assertReportError(thrown, HttpStatus.BAD_REQUEST, "invalid_report");
        verifyNoInteractions(reportRepository, recipeRepository, commentRepository);
    }

    // ---- 404: missing or not live, for everyone ----

    @Test
    void report_missingRecipeOrComment_is404() {
        UUID unknown = UUID.randomUUID();
        when(recipeRepository.findForReviewById(unknown)).thenReturn(Optional.empty());
        when(commentRepository.findRecipeIdById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.report(reporterId, recipeReport(unknown)))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.report(reporterId, commentReport(unknown)))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void report_hiddenRecipe_is404() {
        Recipe hidden = recipe(RecipeStatus.APPROVED, NOW_UTC.minusDays(1));

        assertThatThrownBy(() -> service.report(reporterId, recipeReport(hidden.getId())))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void report_recipeOnlyVisibleToAuthor_is404ForOthers() {
        Recipe rejected = recipe(RecipeStatus.REJECTED, null);

        assertThatThrownBy(() -> service.report(reporterId, recipeReport(rejected.getId())))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        // the 404 comes before the own-content check, so the author gets it too
        assertThatThrownBy(() -> service.report(authorId, recipeReport(rejected.getId())))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void reportComment_onHiddenRecipe_is404() {
        Recipe hidden = recipe(RecipeStatus.APPROVED, NOW_UTC.minusDays(1));
        RecipeComment onHidden = comment(hidden, null);

        assertThatThrownBy(() -> service.report(reporterId, commentReport(onHidden.getId())))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void reportComment_hiddenComment_is404() {
        RecipeComment hidden = comment(recipe, NOW_UTC.minusDays(1));

        assertThatThrownBy(() -> service.report(reporterId, commentReport(hidden.getId())))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        // even its author: the 404 comes first
        assertThatThrownBy(() -> service.report(commenterId, commentReport(hidden.getId())))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        verify(reportRepository, never()).saveAndFlush(any());
    }

    // ---- own_content (403) ----

    @Test
    void report_ownRecipe_isOwnContent403() {
        Throwable thrown = catchThrowable(() ->
                service.report(authorId, recipeReport(recipe.getId())));

        assertReportError(thrown, HttpStatus.FORBIDDEN, "own_content");
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void report_ownComment_isOwnContent403() {
        Throwable thrown = catchThrowable(() ->
                service.report(commenterId, commentReport(comment.getId())));

        assertReportError(thrown, HttpStatus.FORBIDDEN, "own_content");
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void report_recipeAuthor_canReportSomeoneElsesCommentOnIt() {
        service.report(authorId, commentReport(comment.getId()));

        assertThat(savedReport().getCommentId()).isEqualTo(comment.getId());
    }

    @Test
    void report_recipeWithNoAuthor_isNotOwnContent() {
        recipe.setUser(null);

        service.report(reporterId, recipeReport(recipe.getId()));

        assertThat(savedReport().getRecipeId()).isEqualTo(recipe.getId());
    }

    // ---- report_limit (429) ----

    @Test
    void report_21stIn24Hours_isReportLimit429() {
        when(reportRepository.countByReporterIdAndCreatedAtAfter(reporterId, NOW_UTC.minusHours(24)))
                .thenReturn(20L);

        Throwable thrown = catchThrowable(() ->
                service.report(reporterId, recipeReport(recipe.getId())));

        assertReportError(thrown, HttpStatus.TOO_MANY_REQUESTS, "report_limit");
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void report_20thIn24Hours_isAllowed() {
        when(reportRepository.countByReporterIdAndCreatedAtAfter(reporterId, NOW_UTC.minusHours(24)))
                .thenReturn(19L);

        service.report(reporterId, recipeReport(recipe.getId()));

        verify(reportRepository).saveAndFlush(any());
    }

    @Test
    void report_ownContent_comesBeforeTheLimit() {
        when(reportRepository.countByReporterIdAndCreatedAtAfter(eq(authorId), any())).thenReturn(20L);

        Throwable thrown = catchThrowable(() ->
                service.report(authorId, recipeReport(recipe.getId())));

        assertReportError(thrown, HttpStatus.FORBIDDEN, "own_content");
    }

    // ---- already_reported (409) ----

    @Test
    void report_twice_isAlreadyReported409() {
        // the exists check counts every state, so a dismissed first report is also caught here
        when(reportRepository.existsByReporterIdAndRecipeId(reporterId, recipe.getId())).thenReturn(true);
        when(reportRepository.existsByReporterIdAndCommentId(reporterId, comment.getId())).thenReturn(true);

        Throwable onRecipe = catchThrowable(() ->
                service.report(reporterId, recipeReport(recipe.getId())));
        Throwable onComment = catchThrowable(() ->
                service.report(reporterId, commentReport(comment.getId())));

        assertReportError(onRecipe, HttpStatus.CONFLICT, "already_reported");
        assertReportError(onComment, HttpStatus.CONFLICT, "already_reported");
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void report_uniquePairViolation_isAlreadyReported409() {
        when(reportRepository.saveAndFlush(any(ContentReport.class)))
                .thenThrow(new DataIntegrityViolationException("content_reports_reporter_id_recipe_id_key"));

        Throwable thrown = catchThrowable(() ->
                service.report(reporterId, recipeReport(recipe.getId())));

        assertReportError(thrown, HttpStatus.CONFLICT, "already_reported");
    }

    @Test
    void report_otherIntegrityViolation_isNotMappedTo409() {
        DataIntegrityViolationException fk = new DataIntegrityViolationException("content_reports_reporter_id_fkey");
        when(reportRepository.saveAndFlush(any(ContentReport.class))).thenThrow(fk);

        assertThatThrownBy(() -> service.report(reporterId, recipeReport(recipe.getId()))).isSameAs(fk);
    }

    @Test
    void report_limit_comesBeforeAlreadyReported() {
        when(reportRepository.countByReporterIdAndCreatedAtAfter(eq(reporterId), any())).thenReturn(20L);
        when(reportRepository.existsByReporterIdAndRecipeId(reporterId, recipe.getId())).thenReturn(true);

        Throwable thrown = catchThrowable(() ->
                service.report(reporterId, recipeReport(recipe.getId())));

        assertReportError(thrown, HttpStatus.TOO_MANY_REQUESTS, "report_limit");
    }

    // ---- hiding ----

    @Test
    void secondReport_doesNotHide() {
        when(reportRepository.countOpenDistinctReportersForRecipe(recipe.getId())).thenReturn(2L);

        service.report(reporterId, recipeReport(recipe.getId()));

        assertThat(recipe.getHiddenAt()).isNull();
    }

    @Test
    void thirdReport_hidesOnce() {
        when(reportRepository.countOpenDistinctReportersForRecipe(recipe.getId())).thenReturn(3L, 4L);

        service.report(reporterId, recipeReport(recipe.getId()));
        assertThat(recipe.getHiddenAt()).isEqualTo(NOW_UTC);

        // the item is no longer live, so a fourth report is a 404 and hiddenAt stays as it was
        assertThatThrownBy(() -> service.report(UUID.randomUUID(), recipeReport(recipe.getId())))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThat(recipe.getHiddenAt()).isEqualTo(NOW_UTC);
    }

    @Test
    void thirdReport_onAComment_hidesTheCommentOnly() {
        when(reportRepository.countOpenDistinctReportersForComment(comment.getId())).thenReturn(3L);

        service.report(reporterId, commentReport(comment.getId()));

        assertThat(comment.getHiddenAt()).isEqualTo(NOW_UTC);
        assertThat(recipe.getHiddenAt()).isNull();
    }

    @Test
    void thirdReport_onAnAlreadyHiddenItem_keepsItsHiddenAt() {
        // the reporter's lookup raced the hide: the locked row is already hidden, so it is a 404
        OffsetDateTime earlier = NOW_UTC.minusHours(1);
        recipe.setHiddenAt(earlier);
        when(reportRepository.countOpenDistinctReportersForRecipe(recipe.getId())).thenReturn(4L);

        assertThatThrownBy(() -> service.report(reporterId, recipeReport(recipe.getId())))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThat(recipe.getHiddenAt()).isEqualTo(earlier);
    }

    @Test
    void dismissedReports_doNotCountTowardHiding() {
        // two earlier reports were dismissed; only open reports from distinct people are counted
        when(reportRepository.countOpenDistinctReportersForRecipe(recipe.getId())).thenReturn(1L);

        service.report(reporterId, recipeReport(recipe.getId()));

        verify(reportRepository).countOpenDistinctReportersForRecipe(recipe.getId());
        assertThat(recipe.getHiddenAt()).isNull();
    }

    @Test
    void hideCount_isTakenAfterTheInsert() {
        when(reportRepository.countOpenDistinctReportersForRecipe(recipe.getId())).thenReturn(3L);

        service.report(reporterId, recipeReport(recipe.getId()));

        InOrder order = inOrder(reportRepository);
        order.verify(reportRepository).saveAndFlush(any());
        order.verify(reportRepository).countOpenDistinctReportersForRecipe(recipe.getId());
    }

    // ---- the row lock ----

    @Test
    void report_takesTheRowLock() {
        service.report(reporterId, recipeReport(recipe.getId()));
        verify(recipeRepository).findForReviewById(recipe.getId());
        verify(recipeRepository, never()).findById(any());

        clearInvocations(recipeRepository, commentRepository);
        service.report(reporterId, commentReport(comment.getId()));
        // recipe first, then the comment: the same order as an admin removing the recipe
        InOrder order = inOrder(commentRepository, recipeRepository);
        order.verify(commentRepository).findRecipeIdById(comment.getId());
        order.verify(recipeRepository).findForReviewById(recipe.getId());
        order.verify(commentRepository).findForUpdateById(comment.getId());
        verify(commentRepository, never()).findById(any());
    }

    // ---- what the caller has reported ----

    @Test
    void mine_listsOnlyTheReportedOnes() {
        UUID other = UUID.randomUUID();
        when(reportRepository.existsByReporterIdAndRecipeId(reporterId, recipe.getId())).thenReturn(true);
        when(reportRepository.findReportedCommentIds(eq(reporterId), anyCollection()))
                .thenReturn(List.of(comment.getId()));

        MyReportsDto mine = service.mine(reporterId, recipe.getId(), List.of(comment.getId(), other));

        assertThat(mine.recipeIds()).containsExactly(recipe.getId());
        assertThat(mine.commentIds()).containsExactly(comment.getId());
    }

    @Test
    void mine_unreportedRecipe_isEmpty() {
        MyReportsDto mine = service.mine(reporterId, recipe.getId(), null);

        assertThat(mine.recipeIds()).isEmpty();
        assertThat(mine.commentIds()).isEmpty();
        verify(reportRepository, never()).findReportedCommentIds(any(), any());
    }

    @Test
    void mine_nothingAsked_isEmptyWithoutQueries() {
        MyReportsDto mine = service.mine(reporterId, null, List.of());

        assertThat(mine.recipeIds()).isEmpty();
        assertThat(mine.commentIds()).isEmpty();
        verifyNoInteractions(reportRepository);
    }

    @Test
    void mine_over100CommentIds_isInvalidReport400() {
        List<UUID> ids = new ArrayList<>();
        IntStream.range(0, 101).forEach(i -> ids.add(UUID.randomUUID()));

        Throwable thrown = catchThrowable(() ->
                service.mine(reporterId, null, ids));

        assertReportError(thrown, HttpStatus.BAD_REQUEST, "invalid_report");
        verifyNoInteractions(reportRepository);
    }

    @Test
    void mine_100CommentIds_isAllowed() {
        List<UUID> ids = new ArrayList<>();
        IntStream.range(0, 100).forEach(i -> ids.add(UUID.randomUUID()));
        when(reportRepository.findReportedCommentIds(eq(reporterId), anyCollection())).thenReturn(List.of());

        assertThat(service.mine(reporterId, null, ids).commentIds()).isEmpty();
        verify(reportRepository).findReportedCommentIds(eq(reporterId), any(Collection.class));
    }
}
