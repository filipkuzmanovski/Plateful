package mk.ukim.finki.wp.recipeappbackend.ai;

/**
 * No AI provider has an API key configured.
 * The message never contains an API key or a request or response body.
 */
public class AiNotConfiguredException extends RuntimeException {

    public AiNotConfiguredException(String message) {
        super(message);
    }

    public AiNotConfiguredException(String message, Throwable cause) {
        super(message, cause);
    }
}
