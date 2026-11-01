package mk.ukim.finki.wp.recipeappbackend.ai;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import mk.ukim.finki.wp.recipeappbackend.ai.FallbackRecipeAi.NamedRecipeAi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/** Builds the {@code @Primary} {@link RecipeAi} from the active providers in {@code plateful.ai.order}. */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /**
     * Always exists, so the app starts without keys; with no active provider every call throws
     * {@link AiNotConfiguredException}. The project has no auto-configured {@link RestClient.Builder}
     * (no spring-boot-restclient on the classpath), so a plain one is the fallback.
     */
    @Bean
    @Primary
    public RecipeAi recipeAi(AiProperties properties, ObjectProvider<RestClient.Builder> builders) {
        List<NamedRecipeAi> active = activeProviders(properties, builders.getIfAvailable(RestClient::builder));
        log.info("ai providers active={}", active.stream().map(NamedRecipeAi::name).toList());
        return new FallbackRecipeAi(active, Clock.systemUTC());
    }

    /**
     * The photo chain: its own {@link FallbackRecipeAi}, so its cooldowns never touch the text chain.
     * Injected with {@code @Qualifier("visionAi")}.
     */
    @Bean("visionAi")
    public RecipeAi visionAi(AiProperties properties, ObjectProvider<RestClient.Builder> builders) {
        List<NamedRecipeAi> active =
                activeVisionProviders(properties, builders.getIfAvailable(RestClient::builder));
        log.info("ai vision providers active={}", active.stream().map(NamedRecipeAi::name).toList());
        return new FallbackRecipeAi(active, Clock.systemUTC());
    }

    /**
     * The post chain: recipes written from an Instagram post that came with screenshots go through Groq
     * only, with the text model. Its own {@link FallbackRecipeAi}, so its cooldowns never touch the other
     * chains. Injected with {@code @Qualifier("postAi")}.
     */
    @Bean("postAi")
    public RecipeAi postAi(AiProperties properties, ObjectProvider<RestClient.Builder> builders) {
        List<NamedRecipeAi> active =
                activePostProviders(properties, builders.getIfAvailable(RestClient::builder));
        log.info("ai post providers active={}", active.stream().map(NamedRecipeAi::name).toList());
        return new FallbackRecipeAi(active, Clock.systemUTC());
    }

    /** Active providers in configured order. Unknown names are ignored with a warning. */
    static List<NamedRecipeAi> activeProviders(AiProperties properties, RestClient.Builder builder) {
        return build(properties.order(), "plateful.ai.order", properties, builder, AiProperties.Provider::model);
    }

    /** Active providers in {@code plateful.ai.vision-order}, each named with its vision model. */
    static List<NamedRecipeAi> activeVisionProviders(AiProperties properties, RestClient.Builder builder) {
        return build(properties.visionOrder(), "plateful.ai.vision-order", properties, builder,
                AiProperties.Provider::visionModel);
    }

    /** Active providers in {@code plateful.ai.post-order}, each named with its text model. */
    static List<NamedRecipeAi> activePostProviders(AiProperties properties, RestClient.Builder builder) {
        return build(properties.postOrder(), "plateful.ai.post-order", properties, builder,
                AiProperties.Provider::model);
    }

    private static List<NamedRecipeAi> build(List<String> order, String setting, AiProperties properties,
                                             RestClient.Builder builder,
                                             Function<AiProperties.Provider, String> modelOf) {
        List<NamedRecipeAi> result = new ArrayList<>();
        for (String raw : order) {
            String name = raw == null ? "" : raw.trim().toLowerCase();
            if (result.stream().anyMatch(p -> p.name().equals(name))) {
                continue;
            }
            switch (name) {
                case GroqRecipeAi.NAME -> {
                    if (properties.groq().active()) {
                        result.add(new NamedRecipeAi(name, new GroqRecipeAi(builder, properties),
                                modelOf.apply(properties.groq())));
                    }
                }
                case GeminiRecipeAi.NAME -> {
                    if (properties.gemini().active()) {
                        result.add(new NamedRecipeAi(name, new GeminiRecipeAi(builder, properties),
                                modelOf.apply(properties.gemini())));
                    }
                }
                default -> log.warn("{} has unknown provider '{}', ignored", setting, name);
            }
        }
        return result;
    }
}
