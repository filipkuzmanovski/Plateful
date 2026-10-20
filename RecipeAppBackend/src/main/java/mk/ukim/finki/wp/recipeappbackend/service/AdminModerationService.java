package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * An admin removes any recipe or comment, whatever its review status, with a reason that is emailed
 * to the author. The caller has already passed {@link mk.ukim.finki.wp.recipeappbackend.security.AdminGuard}.
 * The reason is checked first, with the same rule as a rejection; the event is published inside the
 * transaction, so the email goes out only if the delete commits.
 */
@Service
public class AdminModerationService {

    private final RecipeRepository recipeRepository;
    private final RecipeCommentRepository commentRepository;
    private final ApplicationEventPublisher events;

    public AdminModerationService(RecipeRepository recipeRepository,
                                  RecipeCommentRepository commentRepository,
                                  ApplicationEventPublisher events) {
        this.recipeRepository = recipeRepository;
        this.commentRepository = commentRepository;
        this.events = events;
    }

    /** Takes the same row lock as an edit or a review, so none of them races the delete. Children cascade. */
    @Transactional
    public void removeRecipe(UUID adminId, UUID recipeId, String reason) {
        String trimmed = AdminReviewService.validReason(reason);
        Recipe recipe = recipeRepository.findForReviewById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe", recipeId));
        User author = recipe.getUser();
        RecipeRemovedEvent event = new RecipeRemovedEvent(
                recipe.getId(),
                recipe.getTitle(),
                trimmed,
                author != null ? author.getEmail() : null,
                author != null ? author.getId() : null,
                adminId);
        recipeRepository.delete(recipe);
        events.publishEvent(event);
    }

    /** No approved-recipe check: an admin can remove a comment on a pending or rejected recipe too. */
    @Transactional
    public void removeComment(UUID adminId, UUID commentId, String reason) {
        String trimmed = AdminReviewService.validReason(reason);
        RecipeComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
        User author = comment.getUser();
        CommentRemovedEvent event = new CommentRemovedEvent(
                comment.getId(),
                comment.getRecipe().getTitle(),
                comment.getBody(),
                trimmed,
                author != null ? author.getEmail() : null,
                author != null ? author.getId() : null,
                adminId);
        commentRepository.delete(comment);
        events.publishEvent(event);
    }
}
