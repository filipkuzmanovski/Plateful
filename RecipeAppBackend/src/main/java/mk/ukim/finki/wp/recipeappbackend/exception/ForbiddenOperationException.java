package mk.ukim.finki.wp.recipeappbackend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an authenticated user tries to touch something they don't own
 * (edit someone else's recipe, delete someone else's comment...). Translates
 * to 403 Forbidden.
 * <p>
 * SECURITY NOTE: with the backend-bypasses-RLS architecture, these service
 * checks are the ONLY authorization layer. Every mutating service method
 * must compare the JWT-derived user id against the row's owner and throw
 * this if they don't match.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ForbiddenOperationException extends RuntimeException {

    public ForbiddenOperationException(String message) {
        super(message);
    }
}
