package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.ForbiddenOperationException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.CommentReactionCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeCommentCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeCommentDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.repository.CommentReactionRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 3.2: a hidden comment is listed only for its author and admins, and takes no edits or new reactions. */
class CommentServiceTest {

    private final UUID recipeAuthorId = UUID.randomUUID();
    private final UUID commenterId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final Pageable page = PageRequest.of(0, 10);

    private RecipeCommentRepository commentRepository;
    private CommentReactionRepository reactionRepository;
    private RecipeVisibility visibility;
    private CommentService commentService;
    private ReactionService reactionService;

    private Recipe recipe;
    private RecipeComment shown;
    private RecipeComment hidden;

    @BeforeEach
    void setUp() {
        commentRepository = mock(RecipeCommentRepository.class);
        reactionRepository = mock(CommentReactionRepository.class);
        RecipeRepository recipeRepository = mock(RecipeRepository.class);
        visibility = mock(RecipeVisibility.class); // recipe visibility is RecipeVisibilityTest's job
        when(visibility.isAdmin(adminId)).thenReturn(true);

        commentService = new CommentService(commentRepository, reactionRepository, recipeRepository,
                mock(UserRepository.class), visibility);
        reactionService = new ReactionService(reactionRepository, commentRepository, visibility);

        recipe = Recipe.builder().id(UUID.randomUUID()).title("Soup").status(RecipeStatus.APPROVED)
                .everApproved(true).user(User.builder().id(recipeAuthorId).build()).build();
        when(recipeRepository.findById(recipe.getId())).thenReturn(Optional.of(recipe));

        shown = comment(null);
        hidden = comment(OffsetDateTime.parse("2026-11-07T10:00:00Z"));
        when(commentRepository.save(any(RecipeComment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private RecipeComment comment(OffsetDateTime hiddenAt) {
        RecipeComment c = RecipeComment.builder()
                .id(UUID.randomUUID())
                .recipe(recipe)
                .user(User.builder().id(commenterId).firstName("Ana").lastName("B").build())
                .body("Too salty")
                .hiddenAt(hiddenAt)
                .build();
        when(commentRepository.findById(c.getId())).thenReturn(Optional.of(c));
        when(commentRepository.findForUpdateById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private static Page<RecipeComment> pageOf(RecipeComment... comments) {
        return new PageImpl<>(List.of(comments));
    }

    // ---- lists ----

    @Test
    void hiddenComment_leftOutForOthers() {
        when(commentRepository.findShownByRecipeId(recipe.getId(), strangerId, page)).thenReturn(pageOf(shown));
        when(commentRepository.findShownByRecipeId(recipe.getId(), null, page)).thenReturn(pageOf(shown));

        Page<RecipeCommentDisplayDto> forStranger = commentService.getForRecipe(strangerId, recipe.getId(), page);
        Page<RecipeCommentDisplayDto> forAnonymous = commentService.getForRecipe(null, recipe.getId(), page);

        assertThat(forStranger.getContent()).extracting(RecipeCommentDisplayDto::id).containsExactly(shown.getId());
        assertThat(forAnonymous.getContent()).extracting(RecipeCommentDisplayDto::id).containsExactly(shown.getId());
        assertThat(forStranger.getContent().getFirst().hidden()).isFalse();
        verify(commentRepository, never()).findByRecipeIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void hiddenComment_shownToItsAuthorWithHiddenTrue() {
        when(commentRepository.findShownByRecipeId(recipe.getId(), commenterId, page))
                .thenReturn(pageOf(hidden, shown));

        Page<RecipeCommentDisplayDto> result = commentService.getForRecipe(commenterId, recipe.getId(), page);

        assertThat(result.getContent()).extracting(RecipeCommentDisplayDto::id)
                .containsExactly(hidden.getId(), shown.getId());
        assertThat(result.getContent()).extracting(RecipeCommentDisplayDto::hidden).containsExactly(true, false);
    }

    @Test
    void hiddenComment_shownToAdmin() {
        when(commentRepository.findByRecipeIdOrderByCreatedAtDesc(recipe.getId(), page))
                .thenReturn(pageOf(hidden, shown));

        Page<RecipeCommentDisplayDto> result = commentService.getForRecipe(adminId, recipe.getId(), page);

        assertThat(result.getContent()).extracting(RecipeCommentDisplayDto::hidden).containsExactly(true, false);
        verify(commentRepository, never()).findShownByRecipeId(any(), any(), any());
    }

    // ---- writes ----

    @Test
    void hiddenComment_refusesReactions() {
        assertThatThrownBy(() -> reactionService.react(strangerId, hidden.getId(), new CommentReactionCreateDto(true)))
                .isExactlyInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> reactionService.react(adminId, hidden.getId(), new CommentReactionCreateDto(false)))
                .isExactlyInstanceOf(ForbiddenOperationException.class);
        verify(reactionRepository, never()).upsertReaction(any(), any(), anyBoolean());

        assertThatCode(() -> reactionService.react(strangerId, shown.getId(), new CommentReactionCreateDto(true)))
                .doesNotThrowAnyException();
    }

    @Test
    void hiddenComment_reactionCanStillBeTakenBack() {
        assertThatCode(() -> reactionService.removeMyReaction(strangerId, hidden.getId()))
                .doesNotThrowAnyException();
        verify(reactionRepository).deleteByCommentIdAndUserId(hidden.getId(), strangerId);
    }

    @Test
    void hiddenComment_reactionList_isNotFoundForOthers() {
        when(reactionRepository.findByCommentId(any(), any())).thenReturn(Page.empty());

        assertThatThrownBy(() -> reactionService.getForComment(strangerId, hidden.getId(), page))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatCode(() -> reactionService.getForComment(commenterId, hidden.getId(), page))
                .doesNotThrowAnyException();
        assertThatCode(() -> reactionService.getForComment(adminId, hidden.getId(), page))
                .doesNotThrowAnyException();
    }

    @Test
    void hiddenComment_refusesEditsEvenByItsAuthor() {
        assertThatThrownBy(() -> commentService.edit(commenterId, hidden.getId(), new RecipeCommentCreateDto("Edited")))
                .isExactlyInstanceOf(ForbiddenOperationException.class);
        assertThat(hidden.getBody()).isEqualTo("Too salty");
        verify(commentRepository, never()).save(any());
    }

    @Test
    void edit_takesTheCommentRowLock() {
        commentService.edit(commenterId, shown.getId(), new RecipeCommentCreateDto("Edited"));

        verify(commentRepository).findForUpdateById(shown.getId());
        verify(commentRepository, never()).findById(any());
        assertThat(shown.getBody()).isEqualTo("Edited");
        assertThat(shown.getHiddenAt()).isNull();
    }

    @Test
    void hiddenComment_canBeDeletedByItsAuthor() {
        commentService.delete(commenterId, hidden.getId());

        verify(commentRepository).delete(hidden);
    }
}
