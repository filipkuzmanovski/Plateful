package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.RecipeHiddenException;
import mk.ukim.finki.wp.recipeappbackend.exception.RecipeNotApprovedException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.CommentReactionCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeCommentCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeRatingCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.repository.CommentReactionRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRatingRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 4.2: reads of comments, ratings and reactions follow recipe visibility (404 when hidden);
 * every write, delete included, needs an approved recipe (404 when hidden, 409 when visible but not approved).
 */
class InteractionReviewGuardTest {

    private final UUID authorId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    private final Pageable page = PageRequest.of(0, 10);

    private RecipeRepository recipeRepository;
    private RecipeCommentRepository commentRepository;
    private CommentReactionRepository reactionRepository;
    private RecipeRatingRepository ratingRepository;
    private CommentService commentService;
    private RatingService ratingService;
    private ReactionService reactionService;

    private Recipe pending;
    private Recipe pendingAfterApproval;
    private Recipe approved;
    private RecipeComment pendingComment;
    private RecipeComment pendingAfterApprovalComment;
    private RecipeComment approvedComment;

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);
        commentRepository = mock(RecipeCommentRepository.class);
        reactionRepository = mock(CommentReactionRepository.class);
        ratingRepository = mock(RecipeRatingRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        RecipeVisibility visibility = new RecipeVisibility(userRepository);

        commentService = new CommentService(commentRepository, reactionRepository, recipeRepository,
                userRepository, visibility);
        ratingService = new RatingService(ratingRepository, recipeRepository, visibility);
        reactionService = new ReactionService(reactionRepository, commentRepository, visibility);

        pending = recipe(RecipeStatus.PENDING, false);
        pendingAfterApproval = recipe(RecipeStatus.PENDING, true);
        approved = recipe(RecipeStatus.APPROVED, true);
        pendingComment = comment(pending);
        pendingAfterApprovalComment = comment(pendingAfterApproval);
        approvedComment = comment(approved);

        when(userRepository.findById(authorId)).thenReturn(Optional.of(User.builder().id(authorId).build()));
        when(commentRepository.findByRecipeIdOrderByCreatedAtDesc(any(), any())).thenReturn(Page.empty());
        when(ratingRepository.findByRecipeId(any(), any())).thenReturn(Page.empty());
        when(reactionRepository.findByCommentId(any(), any())).thenReturn(Page.empty());
        when(commentRepository.save(any(RecipeComment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Recipe recipe(RecipeStatus status, boolean everApproved) {
        Recipe r = Recipe.builder()
                .id(UUID.randomUUID())
                .title("Title")
                .status(status)
                .everApproved(everApproved)
                .approvedTitle(everApproved ? "Old Title" : null)
                .user(User.builder().id(authorId).build())
                .build();
        when(recipeRepository.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    private RecipeComment comment(Recipe recipe) {
        RecipeComment c = RecipeComment.builder()
                .id(UUID.randomUUID())
                .recipe(recipe)
                .user(User.builder().id(authorId).build())
                .body("Tasty")
                .build();
        when(commentRepository.findById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private static final RecipeCommentCreateDto BODY = new RecipeCommentCreateDto("Tasty");
    private static final RecipeRatingCreateDto RATING = new RecipeRatingCreateDto(4);
    private static final CommentReactionCreateDto LIKE = new CommentReactionCreateDto(true);

    // ---- writes on a pending recipe: non-author sees a 404, author gets the 409 ----

    @Test
    void addCommentOnPending() {
        assertThatThrownBy(() -> commentService.add(otherId, pending.getId(), BODY))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> commentService.add(authorId, pending.getId(), BODY))
                .isExactlyInstanceOf(RecipeNotApprovedException.class);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void editCommentOnPending() {
        assertThatThrownBy(() -> commentService.edit(otherId, pendingComment.getId(), BODY))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> commentService.edit(authorId, pendingComment.getId(), BODY))
                .isExactlyInstanceOf(RecipeNotApprovedException.class);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void deleteCommentOnPending() {
        assertThatThrownBy(() -> commentService.delete(otherId, pendingComment.getId()))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> commentService.delete(authorId, pendingComment.getId()))
                .isExactlyInstanceOf(RecipeNotApprovedException.class);
        verify(commentRepository, never()).delete(any());
    }

    @Test
    void rateOnPending() {
        assertThatThrownBy(() -> ratingService.rate(otherId, pending.getId(), RATING))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> ratingService.rate(authorId, pending.getId(), RATING))
                .isExactlyInstanceOf(RecipeNotApprovedException.class);
        verify(ratingRepository, never()).upsertRating(any(), any(), anyInt());
    }

    @Test
    void deleteMyRatingOnPending() {
        assertThatThrownBy(() -> ratingService.deleteMyRating(otherId, pending.getId()))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> ratingService.deleteMyRating(authorId, pending.getId()))
                .isExactlyInstanceOf(RecipeNotApprovedException.class);
        verify(ratingRepository, never()).deleteByRecipeIdAndUserId(any(), any());
    }

    @Test
    void reactOnPending() {
        assertThatThrownBy(() -> reactionService.react(otherId, pendingComment.getId(), LIKE))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> reactionService.react(authorId, pendingComment.getId(), LIKE))
                .isExactlyInstanceOf(RecipeNotApprovedException.class);
        verify(reactionRepository, never()).upsertReaction(any(), any(), anyBoolean());
    }

    @Test
    void removeMyReactionOnPending() {
        assertThatThrownBy(() -> reactionService.removeMyReaction(otherId, pendingComment.getId()))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> reactionService.removeMyReaction(authorId, pendingComment.getId()))
                .isExactlyInstanceOf(RecipeNotApprovedException.class);
        verify(reactionRepository, never()).deleteByCommentIdAndUserId(any(), any());
    }

    // ---- a recipe that was approved once and is back in review: non-author gets the "hidden" 404 ----

    @Test
    void writesOnEditedAfterApprovalGiveTheHidden404ToOthers() {
        assertThatThrownBy(() -> commentService.add(otherId, pendingAfterApproval.getId(), BODY))
                .isExactlyInstanceOf(RecipeHiddenException.class);
        assertThatThrownBy(() -> commentService.delete(otherId, pendingAfterApprovalComment.getId()))
                .isExactlyInstanceOf(RecipeHiddenException.class);
        assertThatThrownBy(() -> ratingService.rate(otherId, pendingAfterApproval.getId(), RATING))
                .isExactlyInstanceOf(RecipeHiddenException.class);
        assertThatThrownBy(() -> reactionService.react(otherId, pendingAfterApprovalComment.getId(), LIKE))
                .isExactlyInstanceOf(RecipeHiddenException.class);
    }

    @Test
    void authorOfEditedAfterApprovalGets409() {
        assertThatThrownBy(() -> commentService.delete(authorId, pendingAfterApprovalComment.getId()))
                .isExactlyInstanceOf(RecipeNotApprovedException.class);
        assertThatThrownBy(() -> ratingService.rate(authorId, pendingAfterApproval.getId(), RATING))
                .isExactlyInstanceOf(RecipeNotApprovedException.class);
    }

    // ---- writes on an approved recipe still work ----

    @Test
    void writesOnApprovedPass() {
        assertThatCode(() -> commentService.add(authorId, approved.getId(), BODY)).doesNotThrowAnyException();
        assertThatCode(() -> commentService.edit(authorId, approvedComment.getId(), BODY)).doesNotThrowAnyException();
        assertThatCode(() -> commentService.delete(authorId, approvedComment.getId())).doesNotThrowAnyException();
        assertThatCode(() -> ratingService.rate(otherId, approved.getId(), RATING)).doesNotThrowAnyException();
        assertThatCode(() -> ratingService.deleteMyRating(otherId, approved.getId())).doesNotThrowAnyException();
        assertThatCode(() -> reactionService.react(otherId, approvedComment.getId(), LIKE)).doesNotThrowAnyException();
        assertThatCode(() -> reactionService.removeMyReaction(otherId, approvedComment.getId()))
                .doesNotThrowAnyException();
    }

    @Test
    void writesOnAnUnknownRecipeOrCommentAre404() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> commentService.add(authorId, unknown, BODY))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> ratingService.deleteMyRating(authorId, unknown))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> reactionService.removeMyReaction(authorId, unknown))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
    }

    // ---- reads: not visible is a 404, visible passes ----

    @Test
    void commentListFollowsVisibility() {
        assertThatThrownBy(() -> commentService.getForRecipe(otherId, pending.getId(), page))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> commentService.getForRecipe(null, pendingAfterApproval.getId(), page))
                .isExactlyInstanceOf(RecipeHiddenException.class);
        assertThatCode(() -> commentService.getForRecipe(null, approved.getId(), page)).doesNotThrowAnyException();
        assertThatCode(() -> commentService.getForRecipe(authorId, pending.getId(), page)).doesNotThrowAnyException();
    }

    @Test
    void ratingListFollowsVisibility() {
        assertThatThrownBy(() -> ratingService.getForRecipe(otherId, pending.getId(), page))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> ratingService.getForRecipe(null, pendingAfterApproval.getId(), page))
                .isExactlyInstanceOf(RecipeHiddenException.class);
        assertThatCode(() -> ratingService.getForRecipe(null, approved.getId(), page)).doesNotThrowAnyException();
    }

    @Test
    void reactionListFollowsVisibility() {
        assertThatThrownBy(() -> reactionService.getForComment(otherId, pendingComment.getId(), page))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> reactionService.getForComment(null, pendingAfterApprovalComment.getId(), page))
                .isExactlyInstanceOf(RecipeHiddenException.class);
        assertThatCode(() -> reactionService.getForComment(null, approvedComment.getId(), page))
                .doesNotThrowAnyException();
    }

    @Test
    void myReactionsFollowVisibility() {
        assertThatThrownBy(() -> commentService.getMyReactions(otherId, pending.getId()))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> commentService.getMyReactions(otherId, pendingAfterApproval.getId()))
                .isExactlyInstanceOf(RecipeHiddenException.class);
        assertThat(commentService.getMyReactions(otherId, approved.getId())).isEmpty();
    }

    @Test
    void myRatingFollowsVisibility() {
        assertThatThrownBy(() -> ratingService.getMyRating(otherId, pending.getId()))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> ratingService.getMyRating(otherId, pendingAfterApproval.getId()))
                .isExactlyInstanceOf(RecipeHiddenException.class);
        assertThat(ratingService.getMyRating(otherId, approved.getId())).isEmpty();
    }
}
