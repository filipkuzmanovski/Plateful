package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.List;

/**
 * What the vision model read from the screenshots of a social media post (spec section 4.2): the dish's name,
 * a short description of how it looks, and the ingredient lines and steps written in the post.
 *
 * @param dish        the dish's name; {@code null} when the post shows none
 * @param description one sentence on what the dish looks like; never null, may be empty
 * @param ingredients ingredient lines copied from the post; never null
 * @param steps       steps copied from the post; never null
 */
public record PostReading(String dish, String description, List<String> ingredients, List<String> steps) {
}
