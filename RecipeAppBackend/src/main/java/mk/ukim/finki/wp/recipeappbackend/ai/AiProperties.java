package mk.ukim.finki.wp.recipeappbackend.ai;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * {@code plateful.ai.*}. A provider is active when its API key is not blank.
 *
 * @param order      provider names in the order they are tried for text
 * @param dailyLimit generations allowed per user per UTC day
 * @param dailyScanLimit photo scans allowed per user per UTC day
 * @param visionOrder provider names tried for photos (default {@code groq} only)
 * @param postOrder  provider names tried for recipes written from an Instagram post that came with
 *                   screenshots (default {@code groq} only)
 * @param nutritionOrder provider names tried for calorie estimates (default {@code groq} only)
 * @param nutritionDailyLimit calorie estimates allowed per UTC day, for the whole app (default 40)
 */
@ConfigurationProperties("plateful.ai")
public record AiProperties(Provider groq, Provider gemini, List<String> order, int dailyLimit,
                           int dailyScanLimit, List<String> visionOrder, List<String> postOrder,
                           List<String> nutritionOrder, int nutritionDailyLimit) {

    static final String GROQ_VISION_MODEL = "qwen/qwen3.8-27b";
    static final String GEMINI_VISION_MODEL = "gemini-3.8-flash";

    @ConstructorBinding
    public AiProperties {
        groq = groq == null ? new Provider("", "") : groq;
        gemini = gemini == null ? new Provider("", "") : gemini;
        groq = groq.visionModel() == null || groq.visionModel().isBlank()
                ? new Provider(groq.apiKey(), groq.model(), GROQ_VISION_MODEL) : groq;
        gemini = gemini.visionModel() == null || gemini.visionModel().isBlank()
                ? new Provider(gemini.apiKey(), gemini.model(), GEMINI_VISION_MODEL) : gemini;
        order = order == null || order.isEmpty() ? List.of("groq", "gemini") : List.copyOf(order);
        dailyLimit = dailyLimit <= 0 ? 10 : dailyLimit;
        dailyScanLimit = dailyScanLimit <= 0 ? 10 : dailyScanLimit;
        visionOrder = visionOrder == null || visionOrder.isEmpty() ? List.of("groq") : List.copyOf(visionOrder);
        postOrder = postOrder == null || postOrder.isEmpty() ? List.of("groq") : List.copyOf(postOrder);
        nutritionOrder = nutritionOrder == null || nutritionOrder.isEmpty()
                ? List.of("groq") : List.copyOf(nutritionOrder);
        nutritionDailyLimit = nutritionDailyLimit <= 0 ? 40 : nutritionDailyLimit;
    }

    /** Without a nutrition order or limit: estimates use {@code groq} only, 40 a day. */
    public AiProperties(Provider groq, Provider gemini, List<String> order, int dailyLimit,
                        int dailyScanLimit, List<String> visionOrder, List<String> postOrder) {
        this(groq, gemini, order, dailyLimit, dailyScanLimit, visionOrder, postOrder, null, 0);
    }

    /** Without a vision or post order: both use {@code groq} only. */
    public AiProperties(Provider groq, Provider gemini, List<String> order, int dailyLimit,
                        int dailyScanLimit) {
        this(groq, gemini, order, dailyLimit, dailyScanLimit, null, null, null, 0);
    }

    /** Without a post order: post recipes use {@code groq} only. */
    public AiProperties(Provider groq, Provider gemini, List<String> order, int dailyLimit,
                        int dailyScanLimit, List<String> visionOrder) {
        this(groq, gemini, order, dailyLimit, dailyScanLimit, visionOrder, null, null, 0);
    }

    public record Provider(String apiKey, String model, String visionModel) {

        @ConstructorBinding
        public Provider {
        }

        /** A blank vision model is filled in by {@link AiProperties}. */
        public Provider(String apiKey, String model) {
            this(apiKey, model, null);
        }

        public boolean active() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** Never prints the key. */
        @Override
        public String toString() {
            return "Provider[model=" + model + ", visionModel=" + visionModel + ", active=" + active() + "]";
        }
    }
}
