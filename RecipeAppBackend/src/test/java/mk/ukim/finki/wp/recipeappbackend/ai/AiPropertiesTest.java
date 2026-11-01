package mk.ukim.finki.wp.recipeappbackend.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AiPropertiesTest {

    private static AiProperties bind(Map<String, String> props) {
        return new Binder(new MapConfigurationPropertySource(props))
                .bind("plateful.ai", AiProperties.class)
                .orElseGet(() -> new AiProperties(null, null, null, 0, 0));
    }

    @Test
    void bindsTheSpecBlock() {
        AiProperties p = bind(Map.of(
                "plateful.ai.groq.api-key", "test-key-123",
                "plateful.ai.groq.model", "openai/gpt-oss-120b",
                "plateful.ai.gemini.api-key", "",
                "plateful.ai.gemini.model", "gemini-3.8-flash",
                "plateful.ai.order", "groq,gemini",
                "plateful.ai.daily-limit", "10"));

        assertThat(p.groq().active()).isTrue();
        assertThat(p.groq().model()).isEqualTo("openai/gpt-oss-120b");
        assertThat(p.gemini().active()).isFalse();
        assertThat(p.order()).containsExactly("groq", "gemini");
        assertThat(p.dailyLimit()).isEqualTo(10);
    }

    @Test
    void defaultsWhenNothingIsSet() {
        AiProperties p = bind(Map.of());

        assertThat(p.groq().active()).isFalse();
        assertThat(p.gemini().active()).isFalse();
        assertThat(p.order()).containsExactly("groq", "gemini");
        assertThat(p.dailyLimit()).isEqualTo(10);
    }

    @Test
    void dailyScanLimit_defaultsToTen() {
        assertThat(bind(Map.of()).dailyScanLimit()).isEqualTo(10);
        assertThat(bind(Map.of("plateful.ai.daily-scan-limit", "0")).dailyScanLimit()).isEqualTo(10);
        assertThat(bind(Map.of("plateful.ai.daily-scan-limit", "5")).dailyScanLimit()).isEqualTo(5);
    }

    @Test
    void toStringNeverShowsTheKey() {
        AiProperties p = bind(Map.of("plateful.ai.groq.api-key", "test-key-123"));

        assertThat(p.toString()).doesNotContain("test-key-123");
    }
}
