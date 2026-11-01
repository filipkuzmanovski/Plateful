package mk.ukim.finki.wp.recipeappbackend.exception;

/**
 * The AI's reply to a photo scan was unreadable twice, or no time was left to retry. Maps to 422
 * {@code scan_failed}.
 */
public class ScanFailedException extends RuntimeException {

    public ScanFailedException() {
        super("Couldn't read the photo. Try again or type your ingredients.");
    }
}
