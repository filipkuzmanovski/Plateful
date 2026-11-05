package mk.ukim.finki.wp.recipeappbackend.model.projection;

import java.util.UUID;

/** One row of the batched like count query in RecipeLikeRepository. */
public record RecipeLikeCount(
        UUID recipeId,
        Long likeCount
) {
}
