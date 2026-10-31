package mk.ukim.finki.wp.recipeappbackend.ai;

import java.util.Map;

/**
 * What to ask a provider.
 *
 * @param systemText      instructions
 * @param userText        the request itself
 * @param jsonSchema      JSON Schema the reply must follow
 * @param temperature     sampling temperature
 * @param maxOutputTokens cap on generated tokens
 */
public record AiRequest(String systemText, String userText, Map<String, Object> jsonSchema,
                        double temperature, int maxOutputTokens) {
}
