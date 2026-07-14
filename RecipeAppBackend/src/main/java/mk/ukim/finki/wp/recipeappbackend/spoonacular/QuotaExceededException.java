package mk.ukim.finki.wp.recipeappbackend.spoonacular;

/**
 * Spoonacular returned HTTP 402: the daily points quota is used up.
 * Signals the import loop to stop fetching but KEEP everything already
 * saved — a re-run after the quota resets continues safely (dedup skips).
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException() {
        super("Spoonacular daily quota exhausted (HTTP 402)");
    }
}
