package mk.ukim.finki.wp.recipeappbackend.ai;

/**
 * A provider failed: timeout, connection failure, 5xx, a rejected request or an unreadable reply.
 * The message never contains an API key or a request or response body.
 */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message) {
        super(message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
