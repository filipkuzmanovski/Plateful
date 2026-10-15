package mk.ukim.finki.wp.recipeappbackend.model;

import java.util.List;

/**
 * The multi-criteria browse filter — every field is optional (null = "don't
 * filter on this"). Bound from query parameters in the controller, e.g.
 * GET /api/recipes?cuisine=italian&cuisine=greek&region=asia&vegan=true&maxReadyInMinutes=30&minRating=4
 * <p>
 * cuisine, region and sea are repeatable params (lists); a recipe matches if it has
 * ANY of the resolved cuisines. Null or empty means "not filtering".
 * <p>
 * Diet flags are Boolean (boxed), not boolean, on purpose — the same trick
 * as CommentReactionCreateDto.isLike: null must mean "not filtering", which
 * a primitive can't express (it would silently become false and filter for
 * non-vegan recipes when the user never asked for that).
 * <p>
 * q is the free-text search (recipe title or ingredient name); null or blank means "no search".
 */
public record RecipeFilter(
        List<String> cuisine,
        List<String> region,
        List<String> sea,
        Boolean dairyFree,
        Boolean glutenFree,
        Boolean vegan,
        Boolean vegetarian,
        Integer maxReadyInMinutes,
        Double minRating,
        String q
) {
    /** True when no criteria are set — lets the service skip filtering. */
    public boolean isEmpty() {
        return isUnset(cuisine) && isUnset(region) && isUnset(sea) && dairyFree == null && glutenFree == null
                && vegan == null && vegetarian == null
                && maxReadyInMinutes == null && minRating == null
                && (q == null || q.isBlank());
    }

    private static boolean isUnset(List<String> values) {
        return values == null || values.isEmpty();
    }
}
