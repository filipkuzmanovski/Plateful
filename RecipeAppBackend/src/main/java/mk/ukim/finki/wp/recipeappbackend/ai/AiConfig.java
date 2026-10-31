package mk.ukim.finki.wp.recipeappbackend.ai;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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

    /** Active providers in configured order. Unknown names are ignored with a warning. */
    static List<NamedRecipeAi> activeProviders(AiProperties properties, RestClient.Builder builder) {
        List<NamedRecipeAi> result = new ArrayList<>();
        for (String raw : properties.order()) {
            String name = raw == null ? "" : raw.trim().toLowerCase();
            if (result.stream().anyMatch(p -> p.name().equals(name))) {
                continue;
            }
            switch (name) {
                case GroqRecipeAi.NAME -> {
                    if (properties.groq().active()) {
                        result.add(new NamedRecipeAi(name, new GroqRecipeAi(builder, properties),
                                properties.groq().model()));
                    }
                }
                case GeminiRecipeAi.NAME -> {
                    if (properties.gemini().active()) {
                        result.add(new NamedRecipeAi(name, new GeminiRecipeAi(builder, properties),
                                properties.gemini().model()));
                    }
                }
                default -> log.warn("plateful.ai.order has unknown provider '{}', ignored", name);
            }
        }
        return result;
    }
}
