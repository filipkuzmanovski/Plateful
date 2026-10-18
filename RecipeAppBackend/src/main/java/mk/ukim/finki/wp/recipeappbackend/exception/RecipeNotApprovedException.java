package mk.ukim.finki.wp.recipeappbackend.exception;

import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;

import java.util.UUID;

/**
 * The viewer can see the recipe but it is not approved, so it cannot take comments, ratings or
 * reactions (including deleting them). GlobalExceptionHandler turns it into a 409 with the code
 * "recipe_not_approved".
 */
public class RecipeNotApprovedException extends RuntimeException {

    private final UUID recipeId;
    private final RecipeStatus status;

    public RecipeNotApprovedException(UUID recipeId, RecipeStatus status) {
        super("Recipe with id " + recipeId + " is not approved");
        this.recipeId = recipeId;
        this.status = status;
    }

    public UUID getRecipeId() {
        return recipeId;
    }

    public RecipeStatus getStatus() {
        return status;
    }
}
