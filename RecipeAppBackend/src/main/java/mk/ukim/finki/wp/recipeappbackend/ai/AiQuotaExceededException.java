package mk.ukim.finki.wp.recipeappbackend.ai;

import java.time.Duration;

/**
 * A provider said its quota or rate limit is used up (HTTP 429 or RESOURCE_EXHAUSTED).
 * The message never contains an API key or a request or response body.
 *
 * <p>{@link #retryAfter()} is how long the provider should be left alone: its {@code Retry-After} header when
 * it sent a usable one, else {@link #DEFAULT_RETRY_AFTER}.
 */
public class AiQuotaExceededException extends RuntimeException {

    public static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(60);

    private final Duration retryAfter;

    public AiQuotaExceededException(String message) {
        this(message, DEFAULT_RETRY_AFTER);
    }

    public AiQuotaExceededException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter == null ? DEFAULT_RETRY_AFTER : retryAfter;
    }

    public AiQuotaExceededException(String message, Throwable cause) {
        super(message, cause);
        this.retryAfter = DEFAULT_RETRY_AFTER;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
