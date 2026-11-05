package mk.ukim.finki.wp.recipeappbackend.nutrition;

/** The AI's answer for one ingredient line: the weight used and the nutrition of the whole line. */
public record NutritionItem(int line, double grams, double calories, double protein, double fat, double carbs) {
}
