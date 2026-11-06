package mk.ukim.finki.wp.recipeappbackend.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
    void nutritionProviders_areGroqOnlyWithTheTextModel() {
        AiProperties properties = new AiProperties(
                new AiProperties.Provider("groq-key", "openai/gpt-oss-120b"),
                new AiProperties.Provider("gemini-key", "gemini-3.8-flash"),
                null, 0, 0);

        List<NamedRecipeAi> providers = AiConfig.activeNutritionProviders(properties, RestClient.builder());

        assertThat(providers).extracting(NamedRecipeAi::name).containsExactly("groq");
        assertThat(providers).extracting(NamedRecipeAi::model).containsExactly("openai/gpt-oss-120b");
    }

    @Test
    void nutritionProviders_noGroqKey_isEmpty() {
        AiProperties properties = new AiProperties(
                new AiProperties.Provider("", "openai/gpt-oss-120b"),
                new AiProperties.Provider("gemini-key", "gemini-3.8-flash"),
                null, 0, 0);

        assertThat(AiConfig.activeNutritionProviders(properties, RestClient.builder())).isEmpty();
    }

    @Test
    void nutritionChain_isItsOwnInstance() {
        AiProperties properties = new AiProperties(null, null, null, 0, 0);
        AiConfig config = new AiConfig();
        ObjectProvider<RestClient.Builder> builders =
                new DefaultListableBeanFactory().getBeanProvider(RestClient.Builder.class);

        assertThat(config.nutritionAi(properties, builders))
                .isNotSameAs(config.recipeAi(properties, builders))
                .isNotSameAs(config.visionAi(properties, builders));
    }
}
