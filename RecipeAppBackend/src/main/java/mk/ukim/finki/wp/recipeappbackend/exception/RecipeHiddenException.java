package mk.ukim.finki.wp.recipeappbackend.exception;

import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;

import java.util.UUID;

/**
 * A recipe that was approved once but is not visible to this viewer right now (edited and waiting
 * for review, or rejected after an edit). GlobalExceptionHandler turns it into a 404 that carries
 * the last approved title, so the client can say "under review" instead of "not found".
 */
public class RecipeHiddenException extends RuntimeException {

    private final UUID recipeId;
    private final String approvedTitle;
    private final RecipeStatus status;

    public RecipeHiddenException(UUID recipeId, String approvedTitle, RecipeStatus status) {
        super("Recipe with id " + recipeId + " is not available");
        this.recipeId = recipeId;
        this.approvedTitle = approvedTitle;
        this.status = status;
    }

    public UUID getRecipeId() {
        return recipeId;
    }

    public String getApprovedTitle() {
        return approvedTitle;
    }

    public RecipeStatus getStatus() {
        return status;
    }
}
