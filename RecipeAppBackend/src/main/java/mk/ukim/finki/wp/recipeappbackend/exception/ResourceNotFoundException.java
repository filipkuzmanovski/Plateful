package mk.ukim.finki.wp.recipeappbackend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * Thrown by services when an id doesn't exist. @ResponseStatus makes Spring
 * translate it to a 404 automatically, so controllers don't need try/catch —
 * they just call the service and let this bubble up.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, UUID id) {
        super(resource + " with id " + id + " not found");
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
