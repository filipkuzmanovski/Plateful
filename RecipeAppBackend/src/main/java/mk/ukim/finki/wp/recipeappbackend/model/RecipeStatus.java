package mk.ukim.finki.wp.recipeappbackend.model;

import java.util.Locale;

/**
 * Where a recipe is in the admin review. The database stores the lowercase
 * text (see RecipeStatusConverter), and so does the API.
 */
public enum RecipeStatus {
    PENDING,
    APPROVED,
    REJECTED;

    /** The lowercase text used in the database column and in JSON. */
    public String text() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static RecipeStatus fromText(String text) {
        return valueOf(text.toUpperCase(Locale.ROOT));
    }
}
