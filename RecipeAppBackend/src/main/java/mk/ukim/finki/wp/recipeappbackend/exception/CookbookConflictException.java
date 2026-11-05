package mk.ukim.finki.wp.recipeappbackend.exception;

/** A valid cookbook request that the current state refuses (the cap, or a recipe no longer available). */
public class CookbookConflictException extends RuntimeException {

    public CookbookConflictException(String message) {
        super(message);
    }
}
