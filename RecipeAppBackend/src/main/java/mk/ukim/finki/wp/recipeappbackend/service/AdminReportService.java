package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.ReportException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.OpenReportDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.ReportedItemDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.ContentReport;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.repository.ContentReportRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The admin queue of reported recipes and comments (spec 3.4) and Dismiss. Removal lives in
 * {@link AdminModerationService}.
 * <p>
 * Dismiss takes the same locks, in the same order, as {@link ReportService} and comment removal: the recipe
 * (for a comment, its recipe) first, then the comment. Inside them it closes the item's open reports; none
 * closed means someone already handled it (409). Because a new report needs the same locks, one saved after
 * the dismissal commits stays open.
 */
@Service
public class AdminReportService {

    private final ContentReportRepository reportRepository;
    private final RecipeRepository recipeRepository;
    private final RecipeCommentRepository commentRepository;

    public AdminReportService(ContentReportRepository reportRepository,
                              RecipeRepository recipeRepository,
                              RecipeCommentRepository commentRepository) {
        this.reportRepository = reportRepository;
        this.recipeRepository = recipeRepository;
        this.commentRepository = commentRepository;
    }

    /**
     * One page of the queue. The order is fixed by the query (open count, oldest open report, id), so any
     * client sort is dropped. Details come in batches: one query per kind for the items, one per kind for
     * their open reports.
     */
    @Transactional(readOnly = true)
    public Page<ReportedItemDto> queue(Pageable pageable) {
        Page<ContentReportRepository.QueueRow> rows =
                reportRepository.findQueue(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));

        List<UUID> recipeIds = idsOf(rows, "recipe");
        List<UUID> commentIds = idsOf(rows, "comment");

        Map<UUID, Recipe> recipes = recipeIds.isEmpty() ? Map.of()
                : recipeRepository.findWithAuthorByIdIn(recipeIds).stream()
                .collect(Collectors.toMap(Recipe::getId, Function.identity()));
        Map<UUID, RecipeComment> comments = commentIds.isEmpty() ? Map.of()
                : commentRepository.findWithAuthorByIdIn(commentIds).stream()
                .collect(Collectors.toMap(RecipeComment::getId, Function.identity()));
        Map<UUID, List<OpenReportDto>> recipeReports = recipeIds.isEmpty() ? Map.of()
                : group(reportRepository.findOpenForRecipes(recipeIds), ContentReport::getRecipeId);
        Map<UUID, List<OpenReportDto>> commentReports = commentIds.isEmpty() ? Map.of()
                : group(reportRepository.findOpenForComments(commentIds), ContentReport::getCommentId);

        List<ReportedItemDto> items = new ArrayList<>();
        for (ContentReportRepository.QueueRow row : rows.getContent()) {
            if ("recipe".equals(row.getKind())) {
                Recipe r = recipes.get(row.getId());
                if (r != null) {
                    items.add(new ReportedItemDto("recipe", r.getId(), r.getId(), r.getTitle(), nameOf(r.getUser()),
                            r.getCreatedAt(), (int) row.getOpenCount(), r.getHiddenAt() != null,
                            recipeReports.getOrDefault(r.getId(), List.of())));
                }
            } else {
                RecipeComment c = comments.get(row.getId());
                if (c != null) {
                    items.add(new ReportedItemDto("comment", c.getId(), c.getRecipe().getId(), c.getBody(),
                            nameOf(c.getUser()), c.getCreatedAt(), (int) row.getOpenCount(), c.getHiddenAt() != null,
                            commentReports.getOrDefault(c.getId(), List.of())));
                }
            }
        }
        // An item deleted between the page query and the detail queries is simply left out of the page.
        return new PageImpl<>(items, rows.getPageable(), rows.getTotalElements());
    }

    /** Items (recipes plus comments) with at least one open report. */
    @Transactional(readOnly = true)
    public long count() {
        return reportRepository.countReportedItems();
    }

    @Transactional
    public void dismissRecipe(UUID recipeId) {
        Recipe recipe = recipeRepository.findForReviewById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe", recipeId));
        if (reportRepository.dismissOpenForRecipe(recipeId) == 0) {
            throw ReportException.alreadyHandled();
        }
        recipe.setHiddenAt(null);
    }

    @Transactional
    public void dismissComment(UUID commentId) {
        UUID recipeId = commentRepository.findRecipeIdById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
        recipeRepository.findForReviewById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
        RecipeComment comment = commentRepository.findForUpdateById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
        if (reportRepository.dismissOpenForComment(commentId) == 0) {
            throw ReportException.alreadyHandled();
        }
        comment.setHiddenAt(null);
    }

    private static List<UUID> idsOf(Page<ContentReportRepository.QueueRow> rows, String kind) {
        return rows.getContent().stream().filter(r -> kind.equals(r.getKind()))
                .map(ContentReportRepository.QueueRow::getId).toList();
    }

    /** Keeps the repository's oldest-first order within each item; the reporter is dropped here. */
    private static Map<UUID, List<OpenReportDto>> group(List<ContentReport> reports,
                                                        Function<ContentReport, UUID> itemId) {
        Map<UUID, List<OpenReportDto>> grouped = new HashMap<>();
        for (ContentReport r : reports) {
            grouped.computeIfAbsent(itemId.apply(r), k -> new ArrayList<>())
                    .add(new OpenReportDto(r.getReason().text(), r.getNote(), r.getCreatedAt()));
        }
        return grouped;
    }

    private static String nameOf(User user) {
        return user == null ? null : (user.getFirstName() + " " + user.getLastName()).trim();
    }
}
