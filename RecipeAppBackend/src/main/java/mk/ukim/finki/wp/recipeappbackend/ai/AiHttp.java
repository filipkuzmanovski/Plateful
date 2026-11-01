package mk.ukim.finki.wp.recipeappbackend.ai;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.regex.Pattern;
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

    /** The shortest and longest cooldown a {@code Retry-After} header can ask for. */
    static final Duration MIN_RETRY_AFTER = Duration.ofSeconds(1);
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(300);
    private static final Pattern RETRY_AFTER_SECONDS = Pattern.compile("\\d+(\\.\\d+)?");

    /**
     * Status, raw body and {@code Retry-After} header (or null) of a provider answer; the body is only
     * parsed, never reported.
     */
    record Response(int status, String body, String retryAfter) {

        Response(int status, String body) {
            this(status, body, null);
        }
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
     * The cooldown a {@code Retry-After} header asks for: a number of seconds (a fraction is rounded up),
     * clamped to {@link #MIN_RETRY_AFTER}..{@link #MAX_RETRY_AFTER}. Missing or unparsable (a negative number,
     * an exponent or the HTTP-date form) gives {@link AiQuotaExceededException#DEFAULT_RETRY_AFTER}.
     */
    static Duration retryAfter(String header) {
        if (header == null || !RETRY_AFTER_SECONDS.matcher(header.strip()).matches()) {
            return AiQuotaExceededException.DEFAULT_RETRY_AFTER;
        }
        String value = header.strip();
        String whole = value.contains(".") ? value.substring(0, value.indexOf('.')) : value;
        if (whole.replaceFirst("^0+", "").length() > 6) {
            return MAX_RETRY_AFTER;
        }
        long seconds = new BigDecimal(value).setScale(0, RoundingMode.CEILING).longValueExact();
        return Duration.ofSeconds(Math.clamp(seconds, MIN_RETRY_AFTER.toSeconds(), MAX_RETRY_AFTER.toSeconds()));
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
            throw new AiQuotaExceededException(message, retryAfter(response.retryAfter()));
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
