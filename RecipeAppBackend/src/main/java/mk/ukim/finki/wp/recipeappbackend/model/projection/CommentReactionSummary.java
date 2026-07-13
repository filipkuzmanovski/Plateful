package mk.ukim.finki.wp.recipeappbackend.model.projection;

import java.util.UUID;

/**
 * BUGFIX (review issue #6, N+1 aggregates): one row of the batched
 * like/dislike count query in CommentReactionRepository — one GROUP BY query
 * per page of comments instead of two COUNT queries per comment.
 */
public record CommentReactionSummary(
        UUID commentId,
        Long likeCount,
        Long dislikeCount
) {
}
