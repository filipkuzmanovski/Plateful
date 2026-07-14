package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Import-run settings. Separate record (not nested in SpoonacularProperties)
 * because the property prefix segment "import" is a Java keyword and cannot
 * be a record component name.
 */
@ConfigurationProperties(prefix = "spoonacular.import")
public record SpoonacularImportProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue({"italian", "mexican", "chinese", "indian", "greek",
                "french", "japanese", "thai", "spanish", "american"}) List<String> cuisines,
        @DefaultValue("40") int recipesPerCuisine,
        @DefaultValue("0") int offset
) {
}
