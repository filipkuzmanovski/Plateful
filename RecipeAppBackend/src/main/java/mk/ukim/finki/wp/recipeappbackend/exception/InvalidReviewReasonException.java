package mk.ukim.finki.wp.recipeappbackend.exception;

/** A rejection reason that is missing, blank or too long. Mapped to 400 under errors.reason. */
public class InvalidReviewReasonException extends RuntimeException {

    public InvalidReviewReasonException(String message) {
        super(message);
    }
}
