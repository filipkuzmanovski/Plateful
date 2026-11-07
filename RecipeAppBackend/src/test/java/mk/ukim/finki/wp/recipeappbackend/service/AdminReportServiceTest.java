package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.ReportException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.ReportReason;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.ReportedItemDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.ContentReport;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.repository.ContentReportRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminReportServiceTest {

    private ContentReportRepository reports;
    private RecipeRepository recipes;
    private RecipeCommentRepository comments;
    private AdminReportService service;

    private final UUID recipeId = UUID.randomUUID();
    private final UUID commentId = UUID.randomUUID();
    private final UUID otherRecipeId = UUID.randomUUID();
    private final UUID reporterId = UUID.randomUUID();
    private final OffsetDateTime t0 = OffsetDateTime.parse("2026-11-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        reports = mock(ContentReportRepository.class);
        recipes = mock(RecipeRepository.class);
        comments = mock(RecipeCommentRepository.class);
        service = new AdminReportService(reports, recipes, comments);
    }

    private static ContentReportRepository.QueueRow row(String kind, UUID id, long count) {
        return new ContentReportRepository.QueueRow() {
            public String getKind() { return kind; }
            public UUID getId() { return id; }
            public long getOpenCount() { return count; }
        };
    }

    private static User user(String first, String last) {
        return User.builder().id(UUID.randomUUID()).firstName(first).lastName(last).email(first + "@example.test").build();
    }

    private ContentReport report(UUID recipe, UUID comment, ReportReason reason, String note, OffsetDateTime at) {
        return ContentReport.builder().id(UUID.randomUUID()).reporterId(reporterId).recipeId(recipe).commentId(comment)
                .reason(reason).note(note).createdAt(at).build();
    }

    // ---- queue ----

    @Test
    void queue_keepsTheRepositoryOrderAndMixesRecipesAndComments() {
        when(reports.findQueue(any(Pageable.class))).thenReturn(new PageImpl<>(
                List.of(row("comment", commentId, 3), row("recipe", recipeId, 2)), PageRequest.of(0, 20), 2));
        Recipe parent = Recipe.builder().id(otherRecipeId).title("Parent").build();
        RecipeComment comment = RecipeComment.builder().id(commentId).recipe(parent).user(user("Cy", "Commenter"))
                .body("Rude words").createdAt(t0).build();
        Recipe recipe = Recipe.builder().id(recipeId).title("Soup").user(user("Al", "Author")).createdAt(t0.plusDays(1))
                .hiddenAt(t0.plusDays(2)).build();
        when(comments.findWithAuthorByIdIn(List.of(commentId))).thenReturn(List.of(comment));
        when(recipes.findWithAuthorByIdIn(List.of(recipeId))).thenReturn(List.of(recipe));
        when(reports.findOpenForComments(List.of(commentId))).thenReturn(List.of(
                report(null, commentId, ReportReason.OFFENSIVE, null, t0)));
        when(reports.findOpenForRecipes(List.of(recipeId))).thenReturn(List.of(
                report(recipeId, null, ReportReason.SPAM, "ad", t0.plusHours(1)),
                report(recipeId, null, ReportReason.OTHER, null, t0.plusHours(2))));

        Page<ReportedItemDto> page = service.queue(PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(ReportedItemDto::kind).containsExactly("comment", "recipe");
        ReportedItemDto c = page.getContent().get(0);
        assertThat(c.text()).isEqualTo("Rude words");
        assertThat(c.authorName()).isEqualTo("Cy Commenter");
        assertThat(c.postedAt()).isEqualTo(t0);
        assertThat(c.hidden()).isFalse();
        assertThat(c.openCount()).isEqualTo(3);
        ReportedItemDto r = page.getContent().get(1);
        assertThat(r.text()).isEqualTo("Soup");
        assertThat(r.authorName()).isEqualTo("Al Author");
        assertThat(r.hidden()).isTrue();
        assertThat(r.reports()).extracting("reason", "note")
                .containsExactly(tuple("spam", "ad"), tuple("other", null));
    }

    @Test
    void queue_commentEntryCarriesItsRecipeId() {
        when(reports.findQueue(any(Pageable.class))).thenReturn(new PageImpl<>(
                List.of(row("comment", commentId, 1), row("recipe", recipeId, 1))));
        Recipe parent = Recipe.builder().id(otherRecipeId).title("Parent").build();
        when(comments.findWithAuthorByIdIn(any())).thenReturn(List.of(RecipeComment.builder().id(commentId)
                .recipe(parent).user(user("Cy", "C")).body("x").build()));
        when(recipes.findWithAuthorByIdIn(any())).thenReturn(List.of(Recipe.builder().id(recipeId).title("T").build()));

        List<ReportedItemDto> items = service.queue(PageRequest.of(0, 20)).getContent();

        assertThat(items.get(0).recipeId()).isEqualTo(otherRecipeId);
        assertThat(items.get(0).id()).isEqualTo(commentId);
        assertThat(items.get(1).recipeId()).isEqualTo(recipeId);
        assertThat(items.get(1).id()).isEqualTo(recipeId);
    }

    @Test
    void queue_importedRecipeHasNullAuthorName() {
        when(reports.findQueue(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row("recipe", recipeId, 1))));
        when(recipes.findWithAuthorByIdIn(any())).thenReturn(List.of(Recipe.builder().id(recipeId).title("T").build()));

        assertThat(service.queue(PageRequest.of(0, 20)).getContent().get(0).authorName()).isNull();
    }

    @Test
    void queue_ignoresAnyClientSort_andLoadsReportsInOneQueryPerKind() {
        when(reports.findQueue(any(Pageable.class))).thenReturn(new PageImpl<>(
                List.of(row("recipe", recipeId, 1), row("recipe", otherRecipeId, 1))));
        when(recipes.findWithAuthorByIdIn(any())).thenReturn(List.of(
                Recipe.builder().id(recipeId).title("A").build(), Recipe.builder().id(otherRecipeId).title("B").build()));

        service.queue(PageRequest.of(2, 7, Sort.by("id")));

        verify(reports).findQueue(PageRequest.of(2, 7));
        verify(reports).findOpenForRecipes(List.of(recipeId, otherRecipeId));
        verify(reports, never()).findOpenForComments(anyCollection());
        verify(comments, never()).findWithAuthorByIdIn(anyCollection());
    }

    @Test
    void queue_neverIncludesReporterIds() {
        when(reports.findQueue(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row("recipe", recipeId, 1))));
        when(recipes.findWithAuthorByIdIn(any())).thenReturn(List.of(Recipe.builder().id(recipeId).title("T").build()));
        when(reports.findOpenForRecipes(any())).thenReturn(List.of(report(recipeId, null, ReportReason.SPAM, "n", t0)));

        String json = JsonMapper.builder().build()
                .writeValueAsString(service.queue(PageRequest.of(0, 20)).getContent());

        assertThat(json).doesNotContainIgnoringCase("reporter").doesNotContain(reporterId.toString());
    }

    @Test
    void count_countsItemsNotReports() {
        when(reports.countReportedItems()).thenReturn(4L);

        assertThat(service.count()).isEqualTo(4L);
    }

    // ---- dismiss ----

    @Test
    void dismiss_clearsHiddenAndClosesReports() {
        Recipe recipe = Recipe.builder().id(recipeId).hiddenAt(t0).build();
        when(recipes.findForReviewById(recipeId)).thenReturn(Optional.of(recipe));
        when(reports.dismissOpenForRecipe(recipeId)).thenReturn(2);

        service.dismissRecipe(recipeId);

        assertThat(recipe.getHiddenAt()).isNull();
    }

    @Test
    void dismissComment_clearsHiddenAndClosesReports() {
        Recipe parent = Recipe.builder().id(recipeId).build();
        RecipeComment comment = RecipeComment.builder().id(commentId).recipe(parent).hiddenAt(t0).build();
        when(comments.findRecipeIdById(commentId)).thenReturn(Optional.of(recipeId));
        when(recipes.findForReviewById(recipeId)).thenReturn(Optional.of(parent));
        when(comments.findForUpdateById(commentId)).thenReturn(Optional.of(comment));
        when(reports.dismissOpenForComment(commentId)).thenReturn(1);

        service.dismissComment(commentId);

        assertThat(comment.getHiddenAt()).isNull();
    }

    @Test
    void dismiss_twice_isAlreadyHandled409() {
        Recipe recipe = Recipe.builder().id(recipeId).hiddenAt(t0).build();
        when(recipes.findForReviewById(recipeId)).thenReturn(Optional.of(recipe));
        when(reports.dismissOpenForRecipe(recipeId)).thenReturn(0);

        assertThatThrownBy(() -> service.dismissRecipe(recipeId))
                .isInstanceOfSatisfying(ReportException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("already_handled");
                });
        assertThat(recipe.getHiddenAt()).isEqualTo(t0);
    }

    @Test
    void dismissComment_twice_isAlreadyHandled409() {
        Recipe parent = Recipe.builder().id(recipeId).build();
        RecipeComment comment = RecipeComment.builder().id(commentId).recipe(parent).hiddenAt(t0).build();
        when(comments.findRecipeIdById(commentId)).thenReturn(Optional.of(recipeId));
        when(recipes.findForReviewById(recipeId)).thenReturn(Optional.of(parent));
        when(comments.findForUpdateById(commentId)).thenReturn(Optional.of(comment));
        when(reports.dismissOpenForComment(commentId)).thenReturn(0);

        assertThatThrownBy(() -> service.dismissComment(commentId))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.getCode()).isEqualTo("already_handled"));
        assertThat(comment.getHiddenAt()).isEqualTo(t0);
    }

    @Test
    void dismiss_missingItem_is404() {
        when(recipes.findForReviewById(recipeId)).thenReturn(Optional.empty());
        when(comments.findRecipeIdById(commentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.dismissRecipe(recipeId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.dismissComment(commentId)).isInstanceOf(ResourceNotFoundException.class);
        verify(reports, never()).dismissOpenForRecipe(any());
        verify(reports, never()).dismissOpenForComment(any());
    }

    @Test
    void dismiss_onlyClosesReportsThatExistedBeforeIt() {
        // The update targets open rows only (dismissOpenFor*) and runs after both locks are held. A report
        // takes the same locks, so one saved after this commits stays open.
        Recipe parent = Recipe.builder().id(recipeId).build();
        RecipeComment comment = RecipeComment.builder().id(commentId).recipe(parent).hiddenAt(t0).build();
        when(comments.findRecipeIdById(commentId)).thenReturn(Optional.of(recipeId));
        when(recipes.findForReviewById(recipeId)).thenReturn(Optional.of(parent));
        when(comments.findForUpdateById(commentId)).thenReturn(Optional.of(comment));
        when(reports.dismissOpenForComment(commentId)).thenReturn(1);

        service.dismissComment(commentId);

        var order = inOrder(recipes, comments, reports);
        order.verify(recipes).findForReviewById(recipeId);
        order.verify(comments).findForUpdateById(commentId);
        order.verify(reports).dismissOpenForComment(commentId);
        verify(reports, never()).saveAll(any());
        verify(reports, never()).deleteAll();
    }

    @Test
    void dismissRecipe_locksBeforeUpdating() {
        Recipe recipe = Recipe.builder().id(recipeId).hiddenAt(t0).build();
        when(recipes.findForReviewById(recipeId)).thenReturn(Optional.of(recipe));
        when(reports.dismissOpenForRecipe(recipeId)).thenReturn(1);

        service.dismissRecipe(recipeId);

        var order = inOrder(recipes, reports);
        order.verify(recipes).findForReviewById(recipeId);
        order.verify(reports).dismissOpenForRecipe(recipeId);
    }
}
