package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

/**
 * The user's AI use for today (UTC): generations used, their daily limit and what is left, then the
 * same three for photo scans.
 */
public record AiUsageDto(int used, int limit, int remaining, int scansUsed, int scanLimit, int scansRemaining) {
}
