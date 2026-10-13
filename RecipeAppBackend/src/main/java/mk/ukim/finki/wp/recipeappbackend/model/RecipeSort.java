package mk.ukim.finki.wp.recipeappbackend.model;

import mk.ukim.finki.wp.recipeappbackend.exception.InvalidSortException;

/** The browse sort orders a client can pick with {@code sortBy}. */
public enum RecipeSort {
    NEWEST("newest"),
    MOST_RATED("most-rated"),
    TOP_RATED("top-rated"),
    MOST_COMMENTS("most-comments");

    private final String key;

    RecipeSort(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** @throws InvalidSortException if {@code raw} is not one of the four keys */
    public static RecipeSort fromKey(String raw) {
        for (RecipeSort sort : values()) {
            if (sort.key.equals(raw)) {
                return sort;
            }
        }
        throw new InvalidSortException("Unknown sort: " + raw);
    }
}
