package mk.ukim.finki.wp.recipeappbackend.exception;

/**
 * An approve or reject that cannot go ahead: the recipe is no longer pending ("not_pending") or its
 * revision moved on since the admin's page was rendered ("recipe_changed"). GlobalExceptionHandler
 * turns it into a 409 carrying the code.
 */
public class ReviewConflictException extends RuntimeException {

    private final String code;

    public ReviewConflictException(String code) {
        super("recipe_changed".equals(code)
                ? "The recipe was edited after you opened it"
                : "The recipe is not waiting for review");
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
