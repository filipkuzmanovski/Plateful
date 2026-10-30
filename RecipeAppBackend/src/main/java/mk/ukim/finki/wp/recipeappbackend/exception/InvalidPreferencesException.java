package mk.ukim.finki.wp.recipeappbackend.exception;

/** The submitted food preferences break a rule (unknown allergen or diet, too many entries). */
public class InvalidPreferencesException extends RuntimeException {

    public InvalidPreferencesException(String message) {
        super(message);
    }
}
