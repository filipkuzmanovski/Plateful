package mk.ukim.finki.wp.recipeappbackend.ai;

/**
 * A provider refused the request because of what it contains (HTTP 400, 413 or 422) or answered with
 * nothing. Retrying the same request is pointless, unlike a timeout or a 5xx.
 */
public class AiRequestRejectedException extends AiUnavailableException {

    public AiRequestRejectedException(String message) {
        super(message);
    }
}
