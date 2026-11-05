package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import mk.ukim.finki.wp.recipeappbackend.nutrition.StoredEstimate;

/**
 * The calorie estimate of one recipe, per serving (spec section 7). {@code status} is {@code ready} with
 * numbers, or {@code pending} or {@code none} with null numbers. All rounding happens in {@link #ready}.
 */
public record NutritionEstimateDto(String status, Integer calories, Integer proteinGrams, Integer fatGrams,
                                   Integer carbsGrams) {

    private static final BigDecimal TEN = BigDecimal.TEN;

    public static NutritionEstimateDto none() {
        return new NutritionEstimateDto("none", null, null, null, null);
    }

    public static NutritionEstimateDto pending() {
        return new NutritionEstimateDto("pending", null, null, null, null);
    }

    /** Whole-recipe totals divided by the servings: calories to the nearest 10 (whole kcal below 10), macros to the gram. */
    public static NutritionEstimateDto ready(StoredEstimate stored, int servings) {
        BigDecimal n = BigDecimal.valueOf(servings);
        BigDecimal kcal = stored.calories().divide(n, 6, RoundingMode.HALF_UP);
        int calories = kcal.compareTo(TEN) < 0
                ? kcal.setScale(0, RoundingMode.HALF_UP).intValue()
                : kcal.divide(TEN, 0, RoundingMode.HALF_UP).multiply(TEN).intValue();
        return new NutritionEstimateDto("ready", calories, grams(stored.proteinGrams(), n),
                grams(stored.fatGrams(), n), grams(stored.carbsGrams(), n));
    }

    private static int grams(BigDecimal total, BigDecimal servings) {
        return total.divide(servings, 6, RoundingMode.HALF_UP).setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
