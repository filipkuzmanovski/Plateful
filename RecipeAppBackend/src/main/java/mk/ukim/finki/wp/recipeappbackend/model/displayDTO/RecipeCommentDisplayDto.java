package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;


import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Same reasoning as ratings — no recipe field, comments are always viewed in
 * the context of the recipe you're already on.
 * likeCount/dislikeCount aren't columns on recipe_comments itself — they're
 * aggregated from comment_reactions, so fromEntity() takes them as separate
 * parameters rather than deriving them from the entity alone. The service
 * layer computes them (one GROUP BY query per page of comments, not one
 * query per comment) and passes them in here.
 */
public record RecipeCommentDisplayDto(
        UUID id,
        UserDisplayDto user,
        String body,
        long likeCount,
        long dislikeCount,
        OffsetDateTime createdAt
) {
    public static RecipeCommentDisplayDto fromEntity(RecipeComment comment, long likeCount, long dislikeCount) {
        return new RecipeCommentDisplayDto(
                comment.getId(),
                UserDisplayDto.fromEntity(comment.getUser()),
                comment.getBody(),
                likeCount,
                dislikeCount,
                comment.getCreatedAt()
        );
    }
}
