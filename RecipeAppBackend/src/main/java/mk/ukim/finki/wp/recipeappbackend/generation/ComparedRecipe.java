package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.Set;

/** A known recipe a draft is compared with: its title and its normalised ingredient names. */
public record ComparedRecipe(String title, Set<String> names) {
}
