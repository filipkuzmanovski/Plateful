package mk.ukim.finki.wp.recipeappbackend.ai;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import mk.ukim.finki.wp.recipeappbackend.ai.FallbackRecipeAi.NamedRecipeAi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.client.RestClient;

class AiConfigTest {

    @Test
    void visionProviders_defaultIsGroqOnly() {
        AiProperties properties = new AiProperties(
                new AiProperties.Provider("groq-key", "openai/gpt-oss-120b"),
                new AiProperties.Provider("gemini-key", "gemini-3.8-flash"),
                null, 0, 0);

        List<NamedRecipeAi> providers = AiConfig.activeVisionProviders(properties, RestClient.builder());

        assertThat(providers).extracting(NamedRecipeAi::name).containsExactly("groq");
        assertThat(providers).extracting(NamedRecipeAi::model).containsExactly("qwen/qwen3.8-27b");
    }

    @Test
    void visionProviders_noGroqKey_isEmpty() {
        AiProperties properties = new AiProperties(
                new AiProperties.Provider("", "openai/gpt-oss-120b"),
                new AiProperties.Provider("gemini-key", "gemini-3.8-flash"),
                null, 0, 0);

        assertThat(AiConfig.activeVisionProviders(properties, RestClient.builder())).isEmpty();
    }

    @Test
    void visionProviders_geminiCanBeConfigured_withItsVisionModel() {
        AiProperties properties = new AiProperties(
                new AiProperties.Provider("groq-key", "g"),
                new AiProperties.Provider("gemini-key", "m", "gemini-vision"),
                null, 10, 10, List.of("gemini", "groq"));

        List<NamedRecipeAi> providers = AiConfig.activeVisionProviders(properties, RestClient.builder());

        assertThat(providers).extracting(NamedRecipeAi::name).containsExactly("gemini", "groq");
        assertThat(providers).extracting(NamedRecipeAi::model)
                .containsExactly("gemini-vision", "qwen/qwen3.8-27b");
    }

    @Test
    void textProviders_stillUseTheTextModels() {
        AiProperties properties = new AiProperties(
                new AiProperties.Provider("groq-key", "openai/gpt-oss-120b"),
                new AiProperties.Provider("gemini-key", "gemini-3.8-flash"),
                null, 0, 0);

        assertThat(AiConfig.activeProviders(properties, RestClient.builder()))
                .extracting(NamedRecipeAi::model).containsExactly("openai/gpt-oss-120b", "gemini-3.8-flash");
    }

    @Test
    void postProviders_areGroqOnlyWithTheTextModel() {
        AiProperties properties = new AiProperties(
                new AiProperties.Provider("groq-key", "openai/gpt-oss-120b"),
                new AiProperties.Provider("gemini-key", "gemini-3.8-flash"),
                null, 0, 0);

        List<NamedRecipeAi> providers = AiConfig.activePostProviders(properties, RestClient.builder());

        assertThat(providers).extracting(NamedRecipeAi::name).containsExactly("groq");
        assertThat(providers).extracting(NamedRecipeAi::model).containsExactly("openai/gpt-oss-120b");
        assertThat(providers.get(0).model()).isNotEqualTo(properties.groq().visionModel());
    }

    @Test
    void postProviders_noGroqKey_isEmpty() {
        AiProperties properties = new AiProperties(
                new AiProperties.Provider("", "openai/gpt-oss-120b"),
                new AiProperties.Provider("gemini-key", "gemini-3.8-flash"),
                null, 0, 0);

        assertThat(AiConfig.activePostProviders(properties, RestClient.builder())).isEmpty();
    }

    @Test
    void postChain_isItsOwnInstance() {
        AiProperties properties = new AiProperties(null, null, null, 0, 0);
        AiConfig config = new AiConfig();
        ObjectProvider<RestClient.Builder> builders =
                new DefaultListableBeanFactory().getBeanProvider(RestClient.Builder.class);

        assertThat(config.postAi(properties, builders))
                .isNotSameAs(config.recipeAi(properties, builders))
                .isNotSameAs(config.visionAi(properties, builders));
    }

    @Test
    void postChain_cooldownIsSeparate() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean quota = new AtomicBoolean(true);
        RecipeAi groq = request -> {
            calls.incrementAndGet();
            if (quota.get()) {
                throw new AiQuotaExceededException("groq quota");
            }
            return new AiReply("{}", "groq", "groq-model", null, null);
        };
        RecipeAi post = new FallbackRecipeAi(List.of(new NamedRecipeAi("groq", groq, "groq-model")), clock);
        RecipeAi text = new FallbackRecipeAi(List.of(new NamedRecipeAi("groq", groq, "groq-model")), clock);
        AiRequest request = new AiRequest("sys", "user", Map.of(), 0.7, 2048);

        assertThatThrownBy(() -> post.complete(request)).isInstanceOf(AiQuotaExceededException.class);
        assertThatThrownBy(() -> post.complete(request)).isInstanceOf(AiQuotaExceededException.class);
        assertThat(calls.get()).as("post instance is cooling down").isEqualTo(1);

        quota.set(false);
        assertThat(text.complete(request).json()).isEqualTo("{}");
        assertThat(calls.get()).as("text instance still calls the provider").isEqualTo(2);
    }
}
