package mk.ukim.finki.wp.recipeappbackend.exception;

/**
 * An uploaded scan photo is unusable (wrong count, too big, not a JPEG, undecodable). Maps to 400
 * {@code invalid_photo}. The message is for logs only and never holds image bytes.
 */
public class InvalidScanPhotoException extends RuntimeException {

    public InvalidScanPhotoException(String message) {
        super(message);
    }
}
