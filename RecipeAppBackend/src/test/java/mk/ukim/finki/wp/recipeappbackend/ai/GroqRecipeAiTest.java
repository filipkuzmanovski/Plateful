package mk.ukim.finki.wp.recipeappbackend.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import mk.ukim.finki.wp.recipeappbackend.generation.RecipeSchema;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GroqRecipeAiTest {

    private static final String KEY = "test-key-123";
    private static final String BASE = "http://groq.test";
    private static final String URL = BASE + "/openai/v1/chat/completions";

    private MockRestServiceServer server;
    private GroqRecipeAi ai;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        AiProperties props = new AiProperties(
                new AiProperties.Provider(KEY, "openai/gpt-oss-120b"),
                new AiProperties.Provider("", "gemini-3.8-flash"),
                List.of("groq", "gemini"), 10, 10);
        ai = new GroqRecipeAi(builder, props, BASE);
    }

    private static AiRequest request() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("title", Map.of("type", "string")));
        return new AiRequest("SYS", "USER", schema, 1.0, 2048);
    }

    private static final String OK_BODY = """
            {"choices":[{"message":{"role":"assistant","content":"{\\"title\\":\\"Soup\\"}"}}],
             "usage":{"prompt_tokens":120,"completion_tokens":45,"total_tokens":165}}""";

    @Test
    void sendsKeyInHeaderNotUrl() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + KEY))
                .andExpect(request -> assertThat(request.getURI().toString()).doesNotContain(KEY))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        ai.complete(request());

        server.verify();
    }

    @Test
    void sendsSchemaTemperatureAndTokens() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.model").value("openai/gpt-oss-120b"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("SYS"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("USER"))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.name").value("recipe"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.type").value("object"))
                .andExpect(jsonPath("$.temperature").value(1.0))
                .andExpect(jsonPath("$.max_completion_tokens").value(2048))
                .andExpect(jsonPath("$.reasoning_effort").value("low"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        ai.complete(request());

        server.verify();
    }

    @Test
    void sentSchemaHasNoBoundKeywordsAnywhere_andCallersSchemaIsUntouched() {
        Map<String, Object> schema = RecipeSchema.meals(java.util.Set.of("breakfast", "dinner"));
        String before = schema.toString();
        String[] bodies = new String[1];
        server.expect(requestTo(URL))
                .andExpect(request -> bodies[0] = ((MockClientHttpRequest) request).getBodyAsString())
                .andExpect(jsonPath("$.response_format.json_schema.schema.type").value("object"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.additionalProperties").value(false))
                .andExpect(jsonPath("$.response_format.json_schema.schema.required[0]").value("breakfast"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.dinner.properties.steps.items.type")
                        .value("string"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.dinner.properties.ingredients"
                        + ".items.additionalProperties").value(false))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.dinner.properties.ingredients"
                        + ".items.required[1]").value("amount"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.dinner.properties.cuisine.type[1]")
                        .value("null"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        ai.complete(new AiRequest("SYS", "USER", schema, 1.0, 2048));

        server.verify();
        for (String keyword : List.of("minLength", "maxLength", "minimum", "maximum", "minItems", "maxItems")) {
            assertThat(bodies[0]).as(keyword).doesNotContain("\"" + keyword + "\"");
        }
        assertThat(schema.toString()).isEqualTo(before);
        assertThat(schema.toString()).contains("minLength", "maxItems", "minimum");
    }

    @Test
    void withoutBounds_keepsPropertiesNamedLikeKeywords() {
        Map<String, Object> schema = Map.of("type", "object", "required", List.of("minimum"),
                "additionalProperties", false,
                "properties", Map.of("minimum", Map.of("type", "integer", "minimum", 1, "maximum", 5)));
        Map<String, Object> cleaned = GroqRecipeAi.withoutBounds(schema);
        assertThat(cleaned).containsKeys("type", "required", "additionalProperties", "properties");
        assertThat(cleaned.get("properties")).isEqualTo(Map.of("minimum", Map.of("type", "integer")));
    }

    @Test
    void parsesReplyAndTokens() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        AiReply reply = ai.complete(request());

        assertThat(reply.json()).isEqualTo("{\"title\":\"Soup\"}");
        assertThat(reply.provider()).isEqualTo("groq");
        assertThat(reply.model()).isEqualTo("openai/gpt-oss-120b");
        assertThat(reply.inputTokens()).isEqualTo(120);
        assertThat(reply.outputTokens()).isEqualTo(45);
    }

    @Test
    void missingUsageGivesNullTokens() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}", MediaType.APPLICATION_JSON));

        AiReply reply = ai.complete(request());

        assertThat(reply.inputTokens()).isNull();
        assertThat(reply.outputTokens()).isNull();
    }

    @Test
    void status429_isQuota() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"rate limit\"}}"));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiQuotaExceededException.class)
                .hasMessage("groq HTTP 429");
    }

    @Test
    void status500_isUnavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessage("groq HTTP 500");
    }

    @Test
    void status400_isUnavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"bad\"}}"));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessage("groq HTTP 400");
    }

    @Test
    void emptyChoices_isUnavailable() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void blankContent_isUnavailable() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"choices\":[{\"message\":{\"content\":\"  \"}}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void unreadableReply_isUnavailable() {
        server.expect(requestTo(URL)).andRespond(withSuccess("not json at all", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void keyNeverInExceptionMessage() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"Invalid API key " + KEY + "\"}}"));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class)
                .satisfies(e -> {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain(KEY);
                    }
                });
    }

    @Test
    void transportFailure_isUnavailableWithoutKey() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessage("groq timeout")
                .satisfies(e -> {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain(KEY);
                    }
                });
    }

    @Test
    void nameIsGroq() {
        assertThat(ai.name()).isEqualTo("groq");
    }

    private AiQuotaExceededException quotaWithRetryAfter(String retryAfter) {
        var response = withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"rate limit\"}}");
        if (retryAfter != null) {
            response = response.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        server.expect(requestTo(URL)).andRespond(response);
        try {
            ai.complete(request());
        } catch (AiQuotaExceededException e) {
            server.verify();
            return e;
        }
        throw new AssertionError("expected a quota exception");
    }

    @Test
    void status429_carriesTheRetryAfterHeader() {
        assertThat(quotaWithRetryAfter("12").retryAfter()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void status429_withoutRetryAfter_defaultsTo60s() {
        assertThat(quotaWithRetryAfter(null).retryAfter()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void status429_withAnUnparsableRetryAfter_defaultsTo60s() {
        assertThat(quotaWithRetryAfter("garbage").retryAfter()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void status429_withAHugeRetryAfter_isClampedTo300s() {
        assertThat(quotaWithRetryAfter("9999").retryAfter()).isEqualTo(Duration.ofSeconds(300));
    }

    private static final byte[] IMAGE_A = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
    private static final byte[] IMAGE_B = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 9, 8};

    private static AiRequest visionRequest(int count) {
        List<AiImage> images = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            images.add(new AiImage("image/jpeg", i % 2 == 0 ? IMAGE_A : IMAGE_B));
        }
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("ingredients", Map.of("type", "array")));
        return new AiRequest("SYS", "Here are 2 photos.", schema, 0.2, 1024, images);
    }

    @Test
    void vision_usesVisionModelAndImageParts() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.model").value("qwen/qwen3.8-27b"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("SYS"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content[0].type").value("text"))
                .andExpect(jsonPath("$.messages[1].content[0].text").value("Here are 2 photos."))
                .andExpect(jsonPath("$.messages[1].content[1].type").value("image_url"))
                .andExpect(jsonPath("$.messages[1].content[1].image_url.url").value(
                        "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(IMAGE_A)))
                .andExpect(jsonPath("$.messages[1].content[2].image_url.url").value(
                        "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(IMAGE_B)))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.response_format.json_schema").doesNotExist())
                .andExpect(jsonPath("$.reasoning_effort").value("none"))
                .andExpect(jsonPath("$.reasoning_format").value("hidden"))
                .andExpect(jsonPath("$.temperature").value(0.2))
                .andExpect(jsonPath("$.max_completion_tokens").value(1024))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        AiReply reply = ai.complete(visionRequest(2));

        server.verify();
        assertThat(reply.model()).isEqualTo("qwen/qwen3.8-27b");
    }

    @Test
    void vision_fourImages_throwsBeforeAnyCall() {
        assertThatThrownBy(() -> ai.complete(visionRequest(4)))
                .isInstanceOf(IllegalArgumentException.class);

        server.verify();
    }

    @Test
    void text_requestHasNoVisionFields() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.model").value("openai/gpt-oss-120b"))
                .andExpect(jsonPath("$.reasoning_format").doesNotExist())
                .andExpect(jsonPath("$.messages[1].content").value("USER"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        ai.complete(request());

        server.verify();
    }

    @Test
    void status413_isUnavailable_notQuota() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.PAYLOAD_TOO_LARGE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"Request too large\"}}"));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessage("groq HTTP 413");
    }
}
