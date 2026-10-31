package mk.ukim.finki.wp.recipeappbackend.generation;

/**
 * Why a draft was rejected, as a short English sentence. It is added to the next attempt's prompt, so it may
 * name an ingredient but never says "allergy" or "diet": those words are not sent to the provider.
 */
public record DraftProblem(String reason) {
}
