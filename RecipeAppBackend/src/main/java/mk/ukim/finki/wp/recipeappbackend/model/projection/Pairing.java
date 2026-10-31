package mk.ukim.finki.wp.recipeappbackend.model.projection;

/**
 * An ingredient that often appears with another: {@code partner} is its normalised name and {@code percent}
 * (0 to 100) is the share of the other ingredient's recipes that also contain it.
 */
public record Pairing(String partner, int percent) {
}
