package mk.ukim.finki.wp.recipeappbackend.exception;

/** The user has not saved food preferences yet, so no AI recipe can be made safely. Maps to 409. */
public class NoPreferencesException extends RuntimeException {

    public NoPreferencesException() {
        super("Set your food preferences first.");
    }
}
