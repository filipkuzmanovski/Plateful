package mk.ukim.finki.wp.recipeappbackend.ai;

/**
 * A provider said its quota or rate limit is used up (HTTP 429 or RESOURCE_EXHAUSTED).
 * The message never contains an API key or a request or response body.
 */
public class AiQuotaExceededException extends RuntimeException {

    public AiQuotaExceededException(String message) {
        super(message);
    }

    public AiQuotaExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
