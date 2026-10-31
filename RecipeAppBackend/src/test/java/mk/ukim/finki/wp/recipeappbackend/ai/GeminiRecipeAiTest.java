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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GeminiRecipeAiTest {

    private static final String KEY = "test-key-123";
    private static final String BASE = "http://gemini.test";
    private static final String URL = BASE + "/v1beta/models/gemini-3.8-flash:generateContent";

    private MockRestServiceServer server;
    private GeminiRecipeAi ai;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        AiProperties props = new AiProperties(
                new AiProperties.Provider("", "openai/gpt-oss-120b"),
                new AiProperties.Provider(KEY, "gemini-3.8-flash"),
                List.of("groq", "gemini"), 10);
        ai = new GeminiRecipeAi(builder, props, BASE);
    }

    private static AiRequest request() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("title", Map.of("type", "string")));
        return new AiRequest("SYS", "USER", schema, 1.0, 4096);
    }

    private static final String OK_BODY = """
            {"candidates":[{"content":{"role":"model","parts":[
               {"text":"{\\"title\\":"},{"text":"\\"Soup\\"}"}]},"finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":130,"candidatesTokenCount":50,
               "thoughtsTokenCount":20,"totalTokenCount":200}}""";

    @Test
    void sendsKeyInHeaderNotUrl() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", KEY))
                .andExpect(request -> assertThat(request.getURI().toString()).doesNotContain(KEY))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        ai.complete(request());

        server.verify();
    }

    @Test
    void sendsSchemaTemperatureAndTokens() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.systemInstruction.parts[0].text").value("SYS"))
                .andExpect(jsonPath("$.contents[0].role").value("user"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value("USER"))
                .andExpect(jsonPath("$.generationConfig.temperature").value(1.0))
                .andExpect(jsonPath("$.generationConfig.maxOutputTokens").value(4096))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andExpect(jsonPath("$.generationConfig.responseJsonSchema.type").value("object"))
                .andExpect(jsonPath("$.generationConfig.thinkingConfig.thinkingLevel").value("low"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        ai.complete(request());

        server.verify();
    }

    @Test
    void parsesReplyAndTokens() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        AiReply reply = ai.complete(request());

        assertThat(reply.json()).isEqualTo("{\"title\":\"Soup\"}");
        assertThat(reply.provider()).isEqualTo("gemini");
        assertThat(reply.model()).isEqualTo("gemini-3.8-flash");
        assertThat(reply.inputTokens()).isEqualTo(130);
        // output = candidates + thoughts, since thinking tokens are billed as output
        assertThat(reply.outputTokens()).isEqualTo(70);
    }

    @Test
    void status429_isQuota() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiQuotaExceededException.class)
                .hasMessage("gemini HTTP 429");
    }

    @Test
    void resourceExhaustedBody_isQuota() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":403,\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"quota\"}}"));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void status500_isUnavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessage("gemini HTTP 500");
    }

    @Test
    void status400_isUnavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}"));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessage("gemini HTTP 400");
    }

    @Test
    void emptyCandidates_isUnavailable() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void keyNeverInExceptionMessage() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"API key not valid: " + KEY + "\"}}"));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class)
                .satisfies(e -> {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain(KEY);
                    }
                });
    }

    @Test
    void candidateWithoutContent_isUnavailable() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void promptFeedbackBlock_isUnavailable() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void transportFailure_isUnavailableWithoutKey() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> ai.complete(request()))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessage("gemini timeout")
                .satisfies(e -> {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain(KEY);
                    }
                });
    }

    @Test
    void nameIsGemini() {
        assertThat(ai.name()).isEqualTo("gemini");
    }
}
