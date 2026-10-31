package mk.ukim.finki.wp.recipeappbackend.exception;

import java.util.List;
import mk.ukim.finki.wp.recipeappbackend.generation.SafetyCheck.Conflict;

/**
 * The generate request itself is wrong: a bad ingredient count, a bad meal type, or ingredients that conflict
 * with the user's food preferences. Maps to 400 {@code invalid_request}. {@code conflicts} is empty unless the
 * ingredients conflicted; each one echoes the requested string so the frontend can mark the right chip.
 */
public class GenerationRequestException extends RuntimeException {

    private final List<Conflict> conflicts;

    public GenerationRequestException(String message) {
        this(message, List.of());
    }

    public GenerationRequestException(String message, List<Conflict> conflicts) {
        super(message);
        this.conflicts = List.copyOf(conflicts);
    }

    public List<Conflict> getConflicts() {
        return conflicts;
    }
}
