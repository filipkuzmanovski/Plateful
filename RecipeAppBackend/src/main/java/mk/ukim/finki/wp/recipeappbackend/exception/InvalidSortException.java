package mk.ukim.finki.wp.recipeappbackend.exception;

/** The client asked to sort browse results by something we don't offer. */
public class InvalidSortException extends RuntimeException {

    public InvalidSortException(String message) {
        super(message);
    }
}
