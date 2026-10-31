package mk.ukim.finki.wp.recipeappbackend.ai;

/**
 * A provider answer: the JSON text plus which provider and model made it. Token counts are null
 * when the provider did not report them.
 */
public record AiReply(String json, String provider, String model, Integer inputTokens, Integer outputTokens) {
}
