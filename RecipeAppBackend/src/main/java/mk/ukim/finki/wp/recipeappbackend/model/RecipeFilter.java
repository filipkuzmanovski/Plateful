package mk.ukim.finki.wp.recipeappbackend.model;

/**
 * The multi-criteria browse filter — every field is optional (null = "don't
 * filter on this"). Bound from query parameters in the controller, e.g.
 * GET /api/recipes?cuisine=italian&vegan=true&maxReadyInMinutes=30&minRating=4
 * <p>
 * Diet flags are Boolean (boxed), not boolean, on purpose — the same trick
 * as CommentReactionCreateDto.isLike: null must mean "not filtering", which
 * a primitive can't express (it would silently become false and filter for
 * non-vegan recipes when the user never asked for that).
 */
public record RecipeFilter(
        String cuisine,
        Boolean dairyFree,
        Boolean glutenFree,
        Boolean vegan,
        Boolean vegetarian,
        Integer maxReadyInMinutes,
        Double minRating
) {
    /** True when no criteria are set — lets the service skip filtering. */
    public boolean isEmpty() {
        return cuisine == null && dairyFree == null && glutenFree == null
                && vegan == null && vegetarian == null
                && maxReadyInMinutes == null && minRating == null;
    }
}
