package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

/** A recipe's video as public URLs plus its length in whole seconds. */
public record RecipeVideoDto(String url, String posterUrl, int durationSeconds) {
}
