package mk.ukim.finki.wp.recipeappbackend.model.projection;

import java.util.UUID;

/**
 * One of the current user's reactions, as returned by
 * CommentReactionRepository.findMyReactionsForRecipe — just enough for the
 * UI to highlight the like/dislike button the user already pressed.
 */
public record MyCommentReaction(
        UUID commentId,
        boolean isLike
) {
}
