package mk.ukim.finki.wp.recipeappbackend.model.createDTO;

import jakarta.validation.constraints.NotNull;
import mk.ukim.finki.wp.recipeappbackend.model.entities.CommentReaction;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;


/**
 * comment and user come from the URL path / JWT, never the body.
 * isLike is a boxed Boolean (not primitive) on purpose — if the client omits
 * this field entirely, a primitive boolean would silently default to false,
 * but @NotNull on the wrapper type actually catches the missing value and
 * rejects the request instead of guessing.
 */
public record CommentReactionCreateDto(
        @NotNull Boolean isLike
) {
    public CommentReaction toEntity(RecipeComment comment, User user) {
        return CommentReaction.builder()
                .comment(comment)
                .user(user)
                .isLike(isLike)
                .build();
    }
}
