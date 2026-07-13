package mk.ukim.finki.wp.recipeappbackend.model.createDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;


/**
 * recipe and user come from the URL path / JWT, never the body. A user can
 * post many of these on the same recipe, so unlike ratings this is always a
 * plain insert, never an upsert.
 * <p>
 * BUGFIX (review issue #3): body was unbounded — @Size cap added so nobody
 * can POST a multi-megabyte comment into the TEXT column.
 */
public record RecipeCommentCreateDto(
        @NotBlank @Size(max = 5_000) String body
) {
    public RecipeComment toEntity(Recipe recipe, User user) {
        return RecipeComment.builder()
                .recipe(recipe)
                .user(user)
                .body(body)
                .build();
    }
}
