package mk.ukim.finki.wp.recipeappbackend.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Gemini {@code generateContent} with {@code responseJsonSchema}. The key travels only in the
 * {@code x-goog-api-key} header, never as a {@code ?key=} URL parameter.
 */
public class GeminiRecipeAi implements RecipeAi {

    static final String NAME = "gemini";
    static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    private static final String PATH = "/v1beta/models/{model}:generateContent";

    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final String url;

    public GeminiRecipeAi(RestClient.Builder builder, AiProperties properties) {
        this(builder.clone().requestFactory(AiHttp.timeoutFactory()), properties, DEFAULT_BASE_URL);
    }

    /** For tests: the builder is used as given, so a bound mock server keeps its request factory. */
    GeminiRecipeAi(RestClient.Builder builder, AiProperties properties, String baseUrl) {
        this.client = builder.build();
        this.apiKey = properties.gemini().apiKey();
        this.model = properties.gemini().model();
        this.url = baseUrl + PATH;
    }

    public String name() {
        return NAME;
    }

    @Override
    public AiReply complete(AiRequest request) {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", request.temperature());
        generationConfig.put("maxOutputTokens", request.maxOutputTokens());
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseJsonSchema", request.jsonSchema());
        generationConfig.put("thinkingConfig", Map.of("thinkingLevel", "low"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", request.systemText()))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", request.userText())))));
        body.put("generationConfig", generationConfig);
        String payload = AiHttp.toJson(NAME, body);

        AiHttp.Response response = AiHttp.execute(NAME, () -> client.post()
                .uri(url, model)
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .exchange((req, res) -> new AiHttp.Response(
                        res.getStatusCode().value(), AiHttp.bodyOf(res.getBody()))));
        AiHttp.requireSuccess(NAME, response, true);

        JsonNode root = AiHttp.parse(NAME, response.body());
        StringBuilder text = new StringBuilder();
        for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
            JsonNode t = part.path("text");
            if (t.isString() && !part.path("thought").asBoolean(false)) {
                text.append(t.asString());
            }
        }
        if (text.toString().isBlank()) {
            throw new AiUnavailableException(NAME + " empty reply");
        }
        JsonNode usage = root.path("usageMetadata");
        Integer candidates = AiHttp.intOrNull(usage.get("candidatesTokenCount"));
        Integer thoughts = AiHttp.intOrNull(usage.get("thoughtsTokenCount"));
        Integer output = candidates == null && thoughts == null
                ? null
                : (candidates == null ? 0 : candidates) + (thoughts == null ? 0 : thoughts);
        return new AiReply(text.toString(), NAME, model,
                AiHttp.intOrNull(usage.get("promptTokenCount")), output);
    }
}
