package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpoonacularPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @EnableConfigurationProperties({SpoonacularProperties.class, SpoonacularImportProperties.class})
    static class TestConfig {
    }

    @Test
    void bindsAllProperties() {
        runner.withPropertyValues(
                "spoonacular.api-key=test-key",
                "spoonacular.base-url=http://localhost:9999",
                "spoonacular.import.enabled=true",
                "spoonacular.import.cuisines=italian,thai",
                "spoonacular.import.recipes-per-cuisine=5",
                "spoonacular.import.offset=10"
        ).run(ctx -> {
            SpoonacularProperties props = ctx.getBean(SpoonacularProperties.class);
            SpoonacularImportProperties imp = ctx.getBean(SpoonacularImportProperties.class);
            assertThat(props.apiKey()).isEqualTo("test-key");
            assertThat(props.baseUrl()).isEqualTo("http://localhost:9999");
            assertThat(imp.enabled()).isTrue();
            assertThat(imp.cuisines()).containsExactly("italian", "thai");
            assertThat(imp.recipesPerCuisine()).isEqualTo(5);
            assertThat(imp.offset()).isEqualTo(10);
        });
    }

    @Test
    void importDisabledByDefaultAndBaseUrlDefaults() {
        runner.run(ctx -> {
            assertThat(ctx.getBean(SpoonacularImportProperties.class).enabled()).isFalse();
            assertThat(ctx.getBean(SpoonacularProperties.class).baseUrl())
                    .isEqualTo("https://api.spoonacular.com");
        });
    }
}
