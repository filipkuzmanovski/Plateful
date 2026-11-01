package mk.ukim.finki.wp.recipeappbackend.ai;

import java.util.List;
import java.util.Map;

/**
 * What to ask a provider.
 *
 * @param systemText      instructions
 * @param userText        the request itself
 * @param jsonSchema      JSON Schema the reply must follow
 * @param temperature     sampling temperature
 * @param maxOutputTokens cap on generated tokens
 * @param images          images for a vision call; never null, empty for a text call
 */
public record AiRequest(String systemText, String userText, Map<String, Object> jsonSchema,
                        double temperature, int maxOutputTokens, List<AiImage> images) {

    public AiRequest {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** A text-only request. */
    public AiRequest(String systemText, String userText, Map<String, Object> jsonSchema,
                     double temperature, int maxOutputTokens) {
        this(systemText, userText, jsonSchema, temperature, maxOutputTokens, List.of());
    }
}
