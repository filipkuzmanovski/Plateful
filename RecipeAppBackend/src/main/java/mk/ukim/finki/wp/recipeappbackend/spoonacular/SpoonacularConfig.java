package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wiring for the Spoonacular integration. These beans always exist (they're
 * cheap and inert); only the import RUNNER is gated behind
 * spoonacular.import.enabled. An empty api key is fine here — the runner
 * validates it just-in-time before any request is made.
 */
@Configuration
public class SpoonacularConfig {

    @Bean
    public RestClient spoonacularRestClient(SpoonacularProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("x-api-key", properties.apiKey() != null ? properties.apiKey() : "")
                .build();
    }

    @Bean
    public SpoonacularClient spoonacularClient(RestClient spoonacularRestClient) {
        return new SpoonacularClient(spoonacularRestClient);
    }

    @Bean
    public SpoonacularRecipeTranslator spoonacularRecipeTranslator() {
        return new SpoonacularRecipeTranslator();
    }
}
