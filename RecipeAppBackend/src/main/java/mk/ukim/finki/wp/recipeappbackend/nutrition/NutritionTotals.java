package mk.ukim.finki.wp.recipeappbackend.nutrition;

import java.util.List;

/** The sums over every line of a recipe. */
public record NutritionTotals(double calories, double protein, double fat, double carbs) {

    public static NutritionTotals of(List<NutritionItem> items) {
        double calories = 0;
        double protein = 0;
        double fat = 0;
        double carbs = 0;
        for (NutritionItem item : items) {
            calories += item.calories();
            protein += item.protein();
            fat += item.fat();
            carbs += item.carbs();
        }
        return new NutritionTotals(calories, protein, fat, carbs);
    }
}
