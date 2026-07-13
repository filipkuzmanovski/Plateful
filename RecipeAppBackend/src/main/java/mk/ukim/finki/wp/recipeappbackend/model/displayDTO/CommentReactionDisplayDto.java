package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;


import mk.ukim.finki.wp.recipeappbackend.model.entities.CommentReaction;

import java.time.OffsetDateTime;

/**
 * One individual reactor on a comment — who they are and whether it was a
 * like or dislike. Backs a "see who reacted" endpoint, separate from the
 * likeCount/dislikeCount already embedded in RecipeCommentDisplayDto (which
 * covers the common case of just showing the numbers). This is a plain
 * per-row mapping, no aggregation needed, since each CommentReaction row is
 * already exactly one reactor.
 */
public record CommentReactionDisplayDto(
        UserDisplayDto user,
        boolean isLike,
        OffsetDateTime createdAt
) {
    public static CommentReactionDisplayDto fromEntity(CommentReaction reaction) {
        return new CommentReactionDisplayDto(
                UserDisplayDto.fromEntity(reaction.getUser()),
                reaction.isLike(),
                reaction.getCreatedAt()
        );
    }
}
