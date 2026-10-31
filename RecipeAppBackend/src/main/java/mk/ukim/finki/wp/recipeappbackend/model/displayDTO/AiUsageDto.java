package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

/** The user's AI generations for today (UTC): used, the daily limit, and what is left. */
public record AiUsageDto(int used, int limit, int remaining) {
}
