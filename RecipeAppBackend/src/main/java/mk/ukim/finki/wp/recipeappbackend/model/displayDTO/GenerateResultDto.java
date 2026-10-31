package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

/** The new recipe and how many generations the user has left today. */
public record GenerateResultDto(GeneratedRecipeDto recipe, int remainingToday) {
}
