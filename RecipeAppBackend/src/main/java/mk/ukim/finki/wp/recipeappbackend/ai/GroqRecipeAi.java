package mk.ukim.finki.wp.recipeappbackend.ai;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Groq chat completions. Text requests use a strict {@code json_schema} response format; image (vision)
 * requests use the vision model with {@code json_object} and reasoning off. The key travels only in the
 * {@code Authorization} header.
 */
public class GroqRecipeAi implements RecipeAi {

    static final String NAME = "groq";
    static final String DEFAULT_BASE_URL = "https://api.groq.com";
    private static final String PATH = "/openai/v1/chat/completions";
    /** Groq's limit for the vision model. */
    static final int MAX_IMAGES = 3;

    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final String visionModel;
    private final String url;

    public GroqRecipeAi(RestClient.Builder builder, AiProperties properties) {
        this(builder.clone().requestFactory(AiHttp.timeoutFactory()), properties, DEFAULT_BASE_URL);
    }

    /** For tests: the builder is used as given, so a bound mock server keeps its request factory. */
    GroqRecipeAi(RestClient.Builder builder, AiProperties properties, String baseUrl) {
        this.client = builder.build();
        this.apiKey = properties.groq().apiKey();
        this.model = properties.groq().model();
        this.visionModel = properties.groq().visionModel();
        this.url = baseUrl + PATH;
    }

    /** Keywords Groq's strict mode may reject; the draft parser enforces every one of these bounds anyway. */
    static final Set<String> UNSUPPORTED_KEYWORDS =
            Set.of("minLength", "maxLength", "minimum", "maximum", "minItems", "maxItems");

    /** A deep copy of the schema without {@link #UNSUPPORTED_KEYWORDS} at any level; the input is not changed. */
    static Map<String, Object> withoutBounds(Map<String, Object> schema) {
        return copyMap(schema);
    }

    /** Copies a schema node, dropping the unsupported keywords; a node under "properties" is a name map. */
    private static Map<String, Object> copyMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (UNSUPPORTED_KEYWORDS.contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            if ("properties".equals(key) && value instanceof Map<?, ?> props) {
                // property names are data, not keywords: keep them all, clean each property's schema
                Map<String, Object> cleaned = new LinkedHashMap<>();
                props.forEach((name, schema) -> cleaned.put(String.valueOf(name), copyValue(schema)));
                copy.put(key, cleaned);
            } else {
                copy.put(key, copyValue(value));
            }
        }
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(GroqRecipeAi::copyValue).toList();
        }
        return value;
    }

    public String name() {
        return NAME;
    }

    @Override
    public AiReply complete(AiRequest request) {
        boolean vision = !request.images().isEmpty();
        if (request.images().size() > MAX_IMAGES) {
            throw new IllegalArgumentException("groq takes at most " + MAX_IMAGES + " images per request");
        }
        String usedModel = vision ? visionModel : model;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", usedModel);
        if (vision) {
            List<Object> parts = new ArrayList<>();
            parts.add(Map.of("type", "text", "text", request.userText()));
            for (AiImage image : request.images()) {
                String url = "data:" + image.mimeType() + ";base64,"
                        + Base64.getEncoder().encodeToString(image.data());
                parts.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
            }
            body.put("messages", List.of(
                    Map.of("role", "system", "content", request.systemText()),
                    Map.of("role", "user", "content", parts)));
            // JSON mode is the documented one with images; the system text spells out the shape.
            body.put("response_format", Map.of("type", "json_object"));
        } else {
            Map<String, Object> jsonSchema = new LinkedHashMap<>();
            jsonSchema.put("name", "recipe");
            jsonSchema.put("strict", true);
            jsonSchema.put("schema", withoutBounds(request.jsonSchema()));
            body.put("messages", List.of(
                    Map.of("role", "system", "content", request.systemText()),
                    Map.of("role", "user", "content", request.userText())));
            body.put("response_format", Map.of("type", "json_schema", "json_schema", jsonSchema));
        }
        body.put("temperature", request.temperature());
        body.put("max_completion_tokens", request.maxOutputTokens());
        if (vision) {
            // Qwen: reasoning off, and a hidden format because JSON mode refuses "raw"
            body.put("reasoning_effort", "none");
            body.put("reasoning_format", "hidden");
        } else {
            // gpt-oss reasoning tokens count against max_completion_tokens and the TPM limit
            body.put("reasoning_effort", "low");
        }
        String payload = AiHttp.toJson(NAME, body);

        AiHttp.Response response = AiHttp.execute(NAME, () -> client.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .exchange((req, res) -> new AiHttp.Response(res.getStatusCode().value(),
                        AiHttp.bodyOf(res.getBody()), res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))));
        AiHttp.requireSuccess(NAME, response, false);

        JsonNode root = AiHttp.parse(NAME, response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isString() || content.asString().isBlank()) {
            throw new AiUnavailableException(NAME + " empty reply");
        }
        JsonNode usage = root.path("usage");
        return new AiReply(content.asString(), NAME, usedModel,
                AiHttp.intOrNull(usage.get("prompt_tokens")),
                AiHttp.intOrNull(usage.get("completion_tokens")));
    }
}
