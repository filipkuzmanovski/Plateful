package mk.ukim.finki.wp.recipeappbackend.ai;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared transport helpers for the providers. Nothing here ever puts a key, a request body or a
 * response body into an exception message.
 */
final class AiHttp {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Status and raw body of a provider answer; the body is only parsed, never reported. */
    record Response(int status, String body) {
    }

    private AiHttp() {
    }

    static ClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    static String bodyOf(java.io.InputStream in) throws IOException {
        return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
    }

    /** Runs the call, turning transport failures into {@link AiUnavailableException}. */
    static Response execute(String provider, Supplier<Response> call) {
        try {
            return call.get();
        } catch (RestClientException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SocketTimeoutException) {
                throw new AiUnavailableException(provider + " timeout", e);
            }
            throw new AiUnavailableException(provider + " connection failed", e);
        }
    }

    static String toJson(String provider, Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new AiUnavailableException(provider + " request could not be built");
        }
    }

    static JsonNode parse(String provider, String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node == null || !node.isObject()) {
                throw new AiUnavailableException(provider + " unreadable reply");
            }
            return node;
        } catch (JacksonException e) {
            throw new AiUnavailableException(provider + " unreadable reply");
        }
    }

    /**
     * Returns normally for 2xx. HTTP 429 is always quota; so is a {@code RESOURCE_EXHAUSTED} error
     * status when {@code bodyCanSignalQuota}. Every other failure is "unavailable".
     */
    static void requireSuccess(String provider, Response response, boolean bodyCanSignalQuota) {
        int status = response.status();
        if (status >= 200 && status < 300) {
            return;
        }
        String message = provider + " HTTP " + status;
        if (status == 429 || (bodyCanSignalQuota && isResourceExhausted(response.body()))) {
            throw new AiQuotaExceededException(message);
        }
        throw new AiUnavailableException(message);
    }

    private static boolean isResourceExhausted(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode status = MAPPER.readTree(body).path("error").path("status");
            return status.isString() && "RESOURCE_EXHAUSTED".equals(status.asString());
        } catch (JacksonException e) {
            return false;
        }
    }

    static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.intValue() : null;
    }
}
