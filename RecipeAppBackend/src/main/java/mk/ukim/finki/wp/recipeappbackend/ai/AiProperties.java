package mk.ukim.finki.wp.recipeappbackend.ai;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code plateful.ai.*}. A provider is active when its API key is not blank.
 *
 * @param order      provider names in the order they are tried
 * @param dailyLimit generations allowed per user per UTC day
 */
@ConfigurationProperties("plateful.ai")
public record AiProperties(Provider groq, Provider gemini, List<String> order, int dailyLimit) {

    public AiProperties {
        groq = groq == null ? new Provider("", "") : groq;
        gemini = gemini == null ? new Provider("", "") : gemini;
        order = order == null || order.isEmpty() ? List.of("groq", "gemini") : List.copyOf(order);
        dailyLimit = dailyLimit <= 0 ? 10 : dailyLimit;
    }

    public record Provider(String apiKey, String model) {

        public boolean active() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** Never prints the key. */
        @Override
        public String toString() {
            return "Provider[model=" + model + ", active=" + active() + "]";
        }
    }
}
