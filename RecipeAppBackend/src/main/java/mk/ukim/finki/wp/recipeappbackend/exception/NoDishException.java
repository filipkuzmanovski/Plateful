package mk.ukim.finki.wp.recipeappbackend.exception;

/**
 * The post's screenshots were unreadable twice, or the AI found no dish in the post. Maps to 422
 * {@code no_dish}.
 */
public class NoDishException extends RuntimeException {

    public NoDishException() {
        super("Couldn't find a dish in this post.");
    }
}
