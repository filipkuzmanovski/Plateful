package mk.ukim.finki.wp.recipeappbackend.exception;

/** The user has used all of today's AI generations. Maps to 429 {@code daily_limit}. */
public class DailyLimitException extends RuntimeException {

    public DailyLimitException() {
        super("You've used today's 10 AI recipes. More tomorrow.");
    }
}
