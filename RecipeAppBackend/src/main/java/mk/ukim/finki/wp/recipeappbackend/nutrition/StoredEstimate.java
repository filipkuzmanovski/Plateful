package mk.ukim.finki.wp.recipeappbackend.nutrition;

import java.math.BigDecimal;

/** A row of {@code nutrition_estimates}: whole-recipe totals, null when the status is failed. */
public record StoredEstimate(String status, BigDecimal calories, BigDecimal proteinGrams,
                             BigDecimal fatGrams, BigDecimal carbsGrams, String ingredientsHash) {
}
