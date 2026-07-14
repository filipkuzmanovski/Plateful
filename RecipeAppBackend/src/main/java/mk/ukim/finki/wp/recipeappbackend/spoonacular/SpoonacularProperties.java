package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Top-level Spoonacular settings. The API key arrives via the
 * SPOONACULAR_API_KEY environment variable (see application.properties) and
 * may legitimately be EMPTY — the app must boot without it. The import
 * runner is the only place that requires it, and validates just-in-time.
 */
@ConfigurationProperties(prefix = "spoonacular")
public record SpoonacularProperties(
        String apiKey,
        @DefaultValue("https://api.spoonacular.com") String baseUrl
) {
}
