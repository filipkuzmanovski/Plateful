package mk.ukim.finki.wp.recipeappbackend.exception;

/** The request to make a cookbook breaks a rule (number of recipes, subtitle). */
public class InvalidCookbookException extends RuntimeException {

    public InvalidCookbookException(String message) {
        super(message);
    }
}
