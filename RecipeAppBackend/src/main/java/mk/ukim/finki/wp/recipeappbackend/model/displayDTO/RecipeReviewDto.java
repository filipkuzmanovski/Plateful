package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;

import java.time.OffsetDateTime;

/**
 * The review block on a recipe. Only the author and admins ever receive it;
 * for everyone else RecipeDisplayDto.review is null. status is the lowercase text.
 */
public record RecipeReviewDto(
        String status,
        String rejectionReason,
        boolean everApproved,
        int revision,
        OffsetDateTime submittedAt
) {
    public static RecipeReviewDto of(Recipe recipe) {
        return new RecipeReviewDto(
                recipe.getStatus().text(),
                recipe.getRejectionReason(),
                recipe.isEverApproved(),
                recipe.getRevision(),
                recipe.getSubmittedAt()
        );
    }
}
