package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.ReportException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reports of recipes and comments (spec 3.3). One transaction per report, inside the item's row lock: the
 * recipe's ({@code findForReviewById}, shared with edits and admin removal), and for a comment the recipe's
 * first and then the comment's, so the lock order matches an admin removing the recipe and cannot deadlock.
 * <p>
 * Checks run in this order: the request itself (400 {@code invalid_report}), the item exists and is live
 * (the plain 404, for everyone, authors and admins included), not your own (403 {@code own_content}), the
 * rolling daily limit (429 {@code report_limit}), not reported before in any state (409
 * {@code already_reported}). Then the report is inserted and the item's open reports from distinct people
 * are counted; at {@value #HIDE_AT} the item is hidden. Nothing here ever un-hides an item.
 */
@Service
public class ReportService {

    static final int HIDE_AT = 3;
    static final int MAX_NOTE = 500;
    static final int DAILY_LIMIT = 20;
    static final int MAX_ASKED_COMMENTS = 100;

    // Postgres' default names for V15's UNIQUE (reporter_id, recipe_id) and UNIQUE (reporter_id, comment_id).
    private static final List<String> UNIQUE_PAIRS = List.of(
            "content_reports_reporter_id_recipe_id_key",
            "content_reports_reporter_id_comment_id_key");

    private final ContentReportRepository reportRepository;
    private final RecipeRepository recipeRepository;
    private final RecipeCommentRepository commentRepository;
    private final Clock clock;

    @Autowired
    public ReportService(ContentReportRepository reportRepository,
                         RecipeRepository recipeRepository,
                         RecipeCommentRepository commentRepository) {
        this(reportRepository, recipeRepository, commentRepository, Clock.systemUTC());
    }

    ReportService(ContentReportRepository reportRepository,
                  RecipeRepository recipeRepository,
                  RecipeCommentRepository commentRepository,
                  Clock clock) {
        this.reportRepository = reportRepository;
        this.recipeRepository = recipeRepository;
        this.commentRepository = commentRepository;
        this.clock = clock;
    }

    @Transactional
    public void report(UUID reporterId, ReportCreateDto dto) {
        if ((dto.recipeId() == null) == (dto.commentId() == null)) {
            throw ReportException.invalid("Report one recipe or one comment.");
        }
        ReportReason reason = validReason(dto.reason());
        String note = validNote(dto.note());

        if (dto.recipeId() != null) {
            reportRecipe(reporterId, dto.recipeId(), reason, note);
        } else {
            reportComment(reporterId, dto.commentId(), reason, note);
        }
    }

    private void reportRecipe(UUID reporterId, UUID recipeId, ReportReason reason, String note) {
        Recipe recipe = lockLiveRecipe(recipeId);
        if (isOwner(recipe.getUser(), reporterId)) {
            throw ReportException.ownContent();
        }
        requireUnderDailyLimit(reporterId);
        if (reportRepository.existsByReporterIdAndRecipeId(reporterId, recipeId)) {
            throw ReportException.alreadyReported();
        }

        insert(ContentReport.builder().reporterId(reporterId).recipeId(recipeId).reason(reason).note(note).build());

        if (recipe.getHiddenAt() == null
                && reportRepository.countOpenDistinctReportersForRecipe(recipeId) >= HIDE_AT) {
            recipe.setHiddenAt(OffsetDateTime.now(clock));
        }
    }

    private void reportComment(UUID reporterId, UUID commentId, ReportReason reason, String note) {
        // The recipe id comes from a scalar query, so the comment entity is first loaded by the locked read
        // below and never answered from a stale, unlocked copy.
        UUID recipeId = commentRepository.findRecipeIdById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
        lockLiveRecipe(recipeId);
        RecipeComment comment = commentRepository.findForUpdateById(commentId)
                .filter(c -> c.getHiddenAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
        if (isOwner(comment.getUser(), reporterId)) {
            throw ReportException.ownContent();
        }
        requireUnderDailyLimit(reporterId);
        if (reportRepository.existsByReporterIdAndCommentId(reporterId, commentId)) {
            throw ReportException.alreadyReported();
        }

        insert(ContentReport.builder().reporterId(reporterId).commentId(commentId).reason(reason).note(note).build());

        if (comment.getHiddenAt() == null
                && reportRepository.countOpenDistinctReportersForComment(commentId) >= HIDE_AT) {
            comment.setHiddenAt(OffsetDateTime.now(clock));
        }
    }

    /**
     * Which of the asked-about items the caller has reported, in any state. At most
     * {@value #MAX_ASKED_COMMENTS} comment ids per request.
     */
    @Transactional(readOnly = true)
    public MyReportsDto mine(UUID reporterId, UUID recipeId, List<UUID> commentIds) {
        List<UUID> asked = commentIds == null ? List.of()
                : commentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (commentIds != null && commentIds.size() > MAX_ASKED_COMMENTS) {
            throw ReportException.invalid("Ask about at most " + MAX_ASKED_COMMENTS + " comments at a time.");
        }

        List<UUID> recipes = recipeId != null && reportRepository.existsByReporterIdAndRecipeId(reporterId, recipeId)
                ? List.of(recipeId) : List.of();
        List<UUID> comments = asked.isEmpty() ? List.of()
                : List.copyOf(reportRepository.findReportedCommentIds(reporterId, asked));
        return new MyReportsDto(recipes, comments);
    }

    /** The row-locked recipe, or the plain 404 when it is missing or not live, whoever asks. */
    private Recipe lockLiveRecipe(UUID recipeId) {
        return recipeRepository.findForReviewById(recipeId)
                .filter(Recipe::isLive)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe", recipeId));
    }

    private static boolean isOwner(User owner, UUID userId) {
        return owner != null && userId.equals(owner.getId());
    }

    /** Every report in the last 24 hours counts, open or dismissed. */
    private void requireUnderDailyLimit(UUID reporterId) {
        OffsetDateTime since = OffsetDateTime.now(clock).minusHours(24);
        if (reportRepository.countByReporterIdAndCreatedAtAfter(reporterId, since) >= DAILY_LIMIT) {
            throw ReportException.limit();
        }
    }

    /**
     * The explicit "already reported" check runs inside the item's lock, so two reports of one item by one
     * person cannot both pass it. The unique pair is still the real guarantee; its violation gives the same
     * 409. Any other integrity failure is not a duplicate and is rethrown.
     */
    private void insert(ContentReport report) {
        try {
            reportRepository.saveAndFlush(report);
        } catch (DataIntegrityViolationException ex) {
            String message = String.valueOf(ex.getMostSpecificCause().getMessage());
            if (UNIQUE_PAIRS.stream().anyMatch(message::contains)) {
                throw ReportException.alreadyReported();
            }
            throw ex;
        }
    }

    private static ReportReason validReason(String reason) {
        try {
            return ReportReason.fromText(reason);
        } catch (IllegalArgumentException ex) {
            throw ReportException.invalid("Pick one of the reasons.");
        }
    }

    /** Trimmed; blank means no note. */
    private static String validNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.length() > MAX_NOTE) {
            throw ReportException.invalid("The note can be at most " + MAX_NOTE + " characters.");
        }
        return trimmed;
    }
}
