package mk.ukim.finki.wp.recipeappbackend.nutrition;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Sanity checks on the AI's per-ingredient answers (spec section 5.3). Returns the first reason, if any. */
public final class NutritionCheck {

    private static final double MAX_KCAL_PER_GRAM = 9;
    private static final double KCAL_SLACK = 5;
    private static final double MACRO_PER_GRAM = 1.05;
    private static final double MACRO_SLACK_GRAMS = 1;
    private static final double MIN_RECIPE_SLACK_KCAL = 50;
    private static final double RECIPE_SLACK_FRACTION = 0.2;
    private static final double MIN_SERVING_KCAL = 1;
    private static final double MAX_SERVING_KCAL = 3000;
    private static final double MAX_SERVING_GRAMS = 3000;

    private NutritionCheck() {
    }

    public static Optional<String> problem(List<NutritionItem> items, int lineCount, int servings) {
        // 1. Lines 1..N, each exactly once.
        Set<Integer> seen = new HashSet<>();
        for (NutritionItem item : items) {
            if (item.line() < 1 || item.line() > lineCount || !seen.add(item.line())) {
                return Optional.of("lines missing or repeated");
            }
        }
        if (seen.size() != lineCount) {
            return Optional.of("lines missing or repeated");
        }
        for (NutritionItem item : items) {
            // 2. Finite and not negative.
            if (!ok(item.grams()) || !ok(item.calories()) || !ok(item.protein()) || !ok(item.fat())
                    || !ok(item.carbs())) {
                return Optional.of("line " + item.line() + " has a negative or invalid number");
            }
            // 3. Macros cannot outweigh the ingredient (5 percent plus 1 g leeway).
            if (item.protein() + item.fat() + item.carbs() > item.grams() * MACRO_PER_GRAM + MACRO_SLACK_GRAMS) {
                return Optional.of("line " + item.line() + " has more protein, fat and carbs than its weight");
            }
            // 4. At most 900 kcal per 100 g, plus 5.
            if (item.calories() > MAX_KCAL_PER_GRAM * item.grams() + KCAL_SLACK) {
                return Optional.of("line " + item.line() + " has too many calories for its weight");
            }
        }
        // 5. Recipe calories match the macros within the larger of 50 kcal and 20 percent.
        NutritionTotals totals = NutritionTotals.of(items);
        double fromMacros = 4 * totals.protein() + 4 * totals.carbs() + 9 * totals.fat();
        double allowed = Math.max(MIN_RECIPE_SLACK_KCAL, RECIPE_SLACK_FRACTION * totals.calories());
        if (Math.abs(totals.calories() - fromMacros) > allowed) {
            return Optional.of("calories do not match protein, fat and carbs");
        }
        // 6. Per serving: 1 to 3000 kcal and at most 3000 g.
        double perServing = totals.calories() / servings;
        if (perServing < MIN_SERVING_KCAL || perServing > MAX_SERVING_KCAL) {
            return Optional.of("calories per serving out of range");
        }
        double grams = items.stream().mapToDouble(NutritionItem::grams).sum();
        if (grams / servings > MAX_SERVING_GRAMS) {
            return Optional.of("weight per serving out of range");
        }
        return Optional.empty();
    }

    private static boolean ok(double v) {
        return Double.isFinite(v) && v >= 0;
    }
}
