package mk.ukim.finki.wp.recipeappbackend.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AiHttpTest {

    @ParameterizedTest
    @CsvSource({
            "12, 12",
            "1, 1",
            "300, 300",
            "9999, 300",
            "0, 1",
            "99999999999999999999, 300",
            "2.5, 3",
            "' 7 ', 7",
    })
    void retryAfter_isSecondsClampedTo1To300(String header, long seconds) {
        assertThat(AiHttp.retryAfter(header)).isEqualTo(Duration.ofSeconds(seconds));
    }

    @Test
    void retryAfter_missingOrUnparsable_is60s() {
        assertThat(AiHttp.retryAfter(null)).isEqualTo(Duration.ofSeconds(60));
        assertThat(AiHttp.retryAfter("")).isEqualTo(Duration.ofSeconds(60));
        assertThat(AiHttp.retryAfter("garbage")).isEqualTo(Duration.ofSeconds(60));
        assertThat(AiHttp.retryAfter("-5")).isEqualTo(Duration.ofSeconds(60));
        assertThat(AiHttp.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isEqualTo(Duration.ofSeconds(60));
        assertThat(AiHttp.retryAfter("1e999999999")).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void quotaException_carriesTheRetryAfter() {
        assertThatThrownBy(() -> AiHttp.requireSuccess("groq", new AiHttp.Response(429, "", "12"), false))
                .isInstanceOfSatisfying(AiQuotaExceededException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(12)));
    }

    @Test
    void quotaException_withoutAHeader_defaultsTo60s() {
        assertThat(new AiQuotaExceededException("q").retryAfter()).isEqualTo(Duration.ofSeconds(60));
        assertThatThrownBy(() -> AiHttp.requireSuccess("groq", new AiHttp.Response(429, ""), false))
                .isInstanceOfSatisfying(AiQuotaExceededException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    void status413_isUnavailable_evenWithARetryAfter() {
        assertThatThrownBy(() -> AiHttp.requireSuccess("groq", new AiHttp.Response(413, "", "5"), true))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessage("groq HTTP 413");
    }
}
