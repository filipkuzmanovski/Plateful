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
                List.of("groq", "gemini"), 10);
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
}
