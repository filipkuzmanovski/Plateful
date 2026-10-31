package mk.ukim.finki.wp.recipeappbackend.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Groq chat completions with a strict {@code json_schema} response format. The key travels only in
 * the {@code Authorization} header.
 */
public class GroqRecipeAi implements RecipeAi {

    static final String NAME = "groq";
    static final String DEFAULT_BASE_URL = "https://api.groq.com";
    private static final String PATH = "/openai/v1/chat/completions";

    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final String url;

    public GroqRecipeAi(RestClient.Builder builder, AiProperties properties) {
        this(builder.clone().requestFactory(AiHttp.timeoutFactory()), properties, DEFAULT_BASE_URL);
    }

    /** For tests: the builder is used as given, so a bound mock server keeps its request factory. */
    GroqRecipeAi(RestClient.Builder builder, AiProperties properties, String baseUrl) {
        this.client = builder.build();
        this.apiKey = properties.groq().apiKey();
        this.model = properties.groq().model();
        this.url = baseUrl + PATH;
    }

    public String name() {
        return NAME;
    }

    @Override
    public AiReply complete(AiRequest request) {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", "recipe");
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", request.jsonSchema());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemText()),
                Map.of("role", "user", "content", request.userText())));
        body.put("response_format", Map.of("type", "json_schema", "json_schema", jsonSchema));
        body.put("temperature", request.temperature());
        body.put("max_completion_tokens", request.maxOutputTokens());
        // gpt-oss reasoning tokens count against max_completion_tokens and the TPM limit
        body.put("reasoning_effort", "low");
        String payload = AiHttp.toJson(NAME, body);

        AiHttp.Response response = AiHttp.execute(NAME, () -> client.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .exchange((req, res) -> new AiHttp.Response(
                        res.getStatusCode().value(), AiHttp.bodyOf(res.getBody()))));
        AiHttp.requireSuccess(NAME, response, false);

        JsonNode root = AiHttp.parse(NAME, response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isString() || content.asString().isBlank()) {
            throw new AiUnavailableException(NAME + " empty reply");
        }
        JsonNode usage = root.path("usage");
        return new AiReply(content.asString(), NAME, model,
                AiHttp.intOrNull(usage.get("prompt_tokens")),
                AiHttp.intOrNull(usage.get("completion_tokens")));
    }
}
