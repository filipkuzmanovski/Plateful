package mk.ukim.finki.wp.recipeappbackend.nutrition;

import java.util.List;
import java.util.Optional;
import mk.ukim.finki.wp.recipeappbackend.ai.AiReply;
import mk.ukim.finki.wp.recipeappbackend.ai.RecipeAi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Asks the AI for per-ingredient nutrition and checks the answer, with one retry (spec section 5.2). It
 * knows nothing about the database. AI exceptions (busy, down, not configured) propagate to the caller.
 */
@Service
public class NutritionEstimator {

    static final int MAX_LINES = 40;
    static final String UNREADABLE = "unreadable reply";

    private final RecipeAi ai;

    public NutritionEstimator(@Qualifier("nutritionAi") RecipeAi ai) {
        this.ai = ai;
    }

    public sealed interface Outcome permits Ready, Failed {
    }

    public record Ready(NutritionTotals totals, List<NutritionItem> items, String provider, String model)
            implements Outcome {
    }

    public record Failed(String reason) implements Outcome {
    }

    public Outcome estimate(String title, Integer servings, List<String> lines) {
        if (servings == null || servings < 1) {
            return new Failed("no servings");
        }
        if (lines == null || lines.isEmpty()) {
            return new Failed("no ingredient lines");
        }
        if (lines.size() > MAX_LINES) {
            return new Failed("too many ingredient lines");
        }
        String reason = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            AiReply reply = ai.complete(NutritionPrompt.request(title, servings, lines, reason));
            Optional<List<NutritionItem>> items = NutritionParser.parse(reply.json());
            if (items.isEmpty()) {
                reason = UNREADABLE;
                continue;
            }
            Optional<String> problem = NutritionCheck.problem(items.get(), lines.size(), servings);
            if (problem.isEmpty()) {
                return new Ready(NutritionTotals.of(items.get()), items.get(), reply.provider(), reply.model());
            }
            reason = problem.get();
        }
        return new Failed(reason);
    }
}
