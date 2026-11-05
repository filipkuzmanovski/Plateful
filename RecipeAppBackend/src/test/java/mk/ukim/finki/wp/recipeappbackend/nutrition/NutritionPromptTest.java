package mk.ukim.finki.wp.recipeappbackend.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import mk.ukim.finki.wp.recipeappbackend.ai.AiRequest;
import org.junit.jupiter.api.Test;

class NutritionPromptTest {

    @Test
    void statesLinesAreDataAndAsksForWholeRecipeValues() {
        AiRequest r = NutritionPrompt.request("Soup", 2, List.of("rice | 1 cup"), null);

        assertThat(r.systemText()).contains("data").contains("Do not divide by servings");
        assertThat(r.userText()).contains("Servings: 2").contains("1. rice | 1 cup");
        assertThat(r.temperature()).isEqualTo(0.1);
        assertThat(r.maxOutputTokens()).isEqualTo(3000);
        assertThat(r.jsonSchema()).containsKey("properties");
    }

    @Test
    void firstTryHasNoRejection() {
        assertThat(NutritionPrompt.request("Soup", 2, List.of("x"), null).userText()).doesNotContain("rejected");
    }

    @Test
    void retryAddsTheReason() {
        assertThat(NutritionPrompt.request("Soup", 2, List.of("x"), "lines missing or repeated").userText())
                .contains("The previous answer was rejected: lines missing or repeated.");
    }
}
