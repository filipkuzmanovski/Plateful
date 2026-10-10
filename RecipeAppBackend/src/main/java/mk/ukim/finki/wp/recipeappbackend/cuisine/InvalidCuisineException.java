package mk.ukim.finki.wp.recipeappbackend.cuisine;

import java.util.List;

public class InvalidCuisineException extends RuntimeException {

    private final List<String> unknown;

    public InvalidCuisineException(List<String> unknown) {
        super("Unknown cuisine: " + String.join(", ", unknown));
        this.unknown = List.copyOf(unknown);
    }

    public List<String> unknown() {
        return unknown;
    }
}
