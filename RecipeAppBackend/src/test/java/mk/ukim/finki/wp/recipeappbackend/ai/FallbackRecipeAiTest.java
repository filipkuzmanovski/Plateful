package mk.ukim.finki.wp.recipeappbackend.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.client.RestClient;

import mk.ukim.finki.wp.recipeappbackend.ai.FallbackRecipeAi.NamedRecipeAi;

class FallbackRecipeAiTest {

    private static final AiRequest REQUEST =
            new AiRequest("secret-system-text", "secret-user-text", Map.of(), 1.0, 2048);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-31T10:00:00Z"));
    private final Logger logger = (Logger) LoggerFactory.getLogger(FallbackRecipeAi.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logger.setLevel(Level.INFO);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
    }

    /** A provider whose next outcome is scripted and whose calls are counted. */
    private static final class Fake implements RecipeAi {
        final AtomicInteger calls = new AtomicInteger();
        private RuntimeException failure;

        Fake failing(RuntimeException failure) {
            this.failure = failure;
            return this;
        }

        void recover() {
            this.failure = null;
        }

        @Override
        public AiReply complete(AiRequest request) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return new AiReply("{}", "fake", "fake-model", 12, 34);
        }
    }

    private static NamedRecipeAi named(String name, Fake fake) {
        return new NamedRecipeAi(name, fake, name + "-model");
    }

    @Test
    void firstSucceeds_secondNotCalled() {
        Fake first = new Fake();
        Fake second = new Fake();
        FallbackRecipeAi ai = new FallbackRecipeAi(
                List.of(named("groq", first), named("gemini", second)), clock);

        AiReply reply = ai.complete(REQUEST);

        assertThat(reply.json()).isEqualTo("{}");
        assertThat(first.calls.get()).isEqualTo(1);
        assertThat(second.calls.get()).isZero();
    }

    @Test
    void quotaOnFirst_fallsToSecond() {
        Fake first = new Fake().failing(new AiQuotaExceededException("groq quota"));
        Fake second = new Fake();
        FallbackRecipeAi ai = new FallbackRecipeAi(
                List.of(named("groq", first), named("gemini", second)), clock);

        AiReply reply = ai.complete(REQUEST);

        assertThat(reply.json()).isEqualTo("{}");
        assertThat(first.calls.get()).isEqualTo(1);
        assertThat(second.calls.get()).isEqualTo(1);
    }

    @Test
    void unavailableOnFirst_fallsToSecondWithoutCooldown() {
        Fake first = new Fake().failing(new AiUnavailableException("groq 503"));
        Fake second = new Fake();
        FallbackRecipeAi ai = new FallbackRecipeAi(
                List.of(named("groq", first), named("gemini", second)), clock);

        ai.complete(REQUEST);
        ai.complete(REQUEST);

        assertThat(first.calls.get()).isEqualTo(2);
        assertThat(second.calls.get()).isEqualTo(2);
    }

    @Test
    void afterQuota_firstSkippedFor60s_thenTriedAgain() {
        Fake first = new Fake().failing(new AiQuotaExceededException("groq quota"));
        Fake second = new Fake();
        FallbackRecipeAi ai = new FallbackRecipeAi(
                List.of(named("groq", first), named("gemini", second)), clock);

        ai.complete(REQUEST);
        assertThat(first.calls.get()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(59));
        ai.complete(REQUEST);
        assertThat(first.calls.get()).as("still cooling down at 59 s").isEqualTo(1);
        assertThat(second.calls.get()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(1));
        first.recover();
        ai.complete(REQUEST);
        assertThat(first.calls.get()).as("tried again at 60 s").isEqualTo(2);
        assertThat(second.calls.get()).isEqualTo(2);
    }

    @Test
    void allQuota_throwsQuota() {
        FallbackRecipeAi ai = new FallbackRecipeAi(List.of(
                named("groq", new Fake().failing(new AiQuotaExceededException("q1"))),
                named("gemini", new Fake().failing(new AiQuotaExceededException("q2")))), clock);

        assertThatThrownBy(() -> ai.complete(REQUEST)).isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void everyProviderCoolingDown_throwsQuotaWithoutCalling() {
        Fake first = new Fake().failing(new AiQuotaExceededException("q1"));
        Fake second = new Fake().failing(new AiQuotaExceededException("q2"));
        FallbackRecipeAi ai = new FallbackRecipeAi(
                List.of(named("groq", first), named("gemini", second)), clock);
        assertThatThrownBy(() -> ai.complete(REQUEST)).isInstanceOf(AiQuotaExceededException.class);

        clock.advance(Duration.ofSeconds(30));

        assertThatThrownBy(() -> ai.complete(REQUEST)).isInstanceOf(AiQuotaExceededException.class);
        assertThat(first.calls.get()).isEqualTo(1);
        assertThat(second.calls.get()).isEqualTo(1);
    }

    @Test
    void quotaAndUnavailable_throwsUnavailable() {
        FallbackRecipeAi ai = new FallbackRecipeAi(List.of(
                named("groq", new Fake().failing(new AiQuotaExceededException("q"))),
                named("gemini", new Fake().failing(new AiUnavailableException("down")))), clock);

        assertThatThrownBy(() -> ai.complete(REQUEST)).isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void unavailableAndCoolingDown_throwsUnavailable() {
        Fake first = new Fake().failing(new AiQuotaExceededException("q"));
        Fake second = new Fake().failing(new AiUnavailableException("down"));
        FallbackRecipeAi ai = new FallbackRecipeAi(
                List.of(named("groq", first), named("gemini", second)), clock);
        assertThatThrownBy(() -> ai.complete(REQUEST)).isInstanceOf(AiUnavailableException.class);

        clock.advance(Duration.ofSeconds(10));

        assertThatThrownBy(() -> ai.complete(REQUEST)).isInstanceOf(AiUnavailableException.class);
        assertThat(first.calls.get()).isEqualTo(1);
    }

    @Test
    void noProviders_throwsNotConfigured() {
        FallbackRecipeAi ai = new FallbackRecipeAi(List.of(), clock);

        assertThatThrownBy(() -> ai.complete(REQUEST)).isInstanceOf(AiNotConfiguredException.class);
    }

    @Test
    void logsOneInfoLinePerCall_withoutPromptOrReply() {
        FallbackRecipeAi ai = new FallbackRecipeAi(List.of(
                named("groq", new Fake().failing(new AiQuotaExceededException("q"))),
                named("gemini", new Fake())), clock);

        ai.complete(REQUEST);

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
        assertThat(events.get(0).getFormattedMessage())
                .matches("ai call provider=groq model=groq-model outcome=quota ms=\\d+ in=- out=-");
        assertThat(events.get(1).getFormattedMessage())
                .matches("ai call provider=gemini model=fake-model outcome=ok ms=\\d+ in=12 out=34");
        assertThat(events).allSatisfy(e -> assertThat(e.getFormattedMessage())
                .doesNotContain("secret-system-text").doesNotContain("secret-user-text"));
    }

    @Test
    void config_orderGeminiGroq_withOnlyGroqKey_buildsGroqOnly() {
        AiProperties properties = new AiProperties(
                new AiProperties.Provider("test-groq-key", "openai/gpt-oss-120b"),
                new AiProperties.Provider("", "gemini-3.8-flash"),
                List.of("gemini", "groq"), 10);

        List<NamedRecipeAi> providers = AiConfig.activeProviders(properties, RestClient.builder());

        assertThat(providers).extracting(NamedRecipeAi::name).containsExactly("groq");
        assertThat(providers.get(0).model()).isEqualTo("openai/gpt-oss-120b");
        assertThat(providers.get(0).ai()).isInstanceOf(GroqRecipeAi.class);
    }

    @Test
    void config_keepsConfiguredOrder_andIgnoresUnknownNames() {
        AiProperties properties = new AiProperties(
                new AiProperties.Provider("test-groq-key", "g-model"),
                new AiProperties.Provider("test-gemini-key", "m-model"),
                List.of("gemini", "mystery", "groq"), 10);

        List<NamedRecipeAi> providers = AiConfig.activeProviders(properties, RestClient.builder());

        assertThat(providers).extracting(NamedRecipeAi::name).containsExactly("gemini", "groq");
    }

    @Test
    void config_noKeys_buildsEmptyList() {
        AiProperties properties = new AiProperties(null, null, null, 0);

        assertThat(AiConfig.activeProviders(properties, RestClient.builder())).isEmpty();
        assertThatThrownBy(() -> new AiConfig().recipeAi(properties,
                new DefaultListableBeanFactory().getBeanProvider(RestClient.Builder.class)).complete(REQUEST))
                .isInstanceOf(AiNotConfiguredException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
