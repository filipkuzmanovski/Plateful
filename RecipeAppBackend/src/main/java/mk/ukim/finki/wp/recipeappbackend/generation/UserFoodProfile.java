package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mk.ukim.finki.wp.recipeappbackend.food.IngredientNames;
import mk.ukim.finki.wp.recipeappbackend.model.entities.UserPreferences;

/**
 * What the safety check needs to know about a user. {@code allergens} and {@code diets} are food-rule keys;
 * {@code dislikes} and {@code likedIngredients} are normalised ingredient names.
 */
public record UserFoodProfile(
        Set<String> allergens,
        Set<String> diets,
        Set<String> dislikes,
        Set<String> likedIngredients,
        List<String> likedCuisines) {

    public static UserFoodProfile of(UserPreferences p) {
        return new UserFoodProfile(
                keys(p.getAllergens()),
                keys(p.getDiets()),
                names(p.getDislikedIngredients()),
                names(p.getLikedIngredients()),
                p.getLikedCuisines() == null ? List.of() : List.copyOf(p.getLikedCuisines()));
    }

    private static Set<String> keys(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String key : raw) {
                if (key != null) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    private static Set<String> names(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String name : raw) {
                IngredientNames.normalise(name).ifPresent(out::add);
            }
        }
        return out;
    }
}
