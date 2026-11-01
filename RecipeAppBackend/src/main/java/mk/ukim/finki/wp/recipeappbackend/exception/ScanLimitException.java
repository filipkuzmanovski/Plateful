package mk.ukim.finki.wp.recipeappbackend.exception;

/** The user has used all of today's photo scans. Maps to 429 {@code scan_limit}. */
public class ScanLimitException extends RuntimeException {

    public ScanLimitException() {
        super("You've used today's 10 photo scans. More tomorrow.");
    }
}
