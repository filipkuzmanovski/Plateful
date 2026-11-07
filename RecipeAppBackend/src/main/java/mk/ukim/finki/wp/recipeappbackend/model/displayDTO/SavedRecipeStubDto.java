package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import java.util.UUID;

/**
 * A saved recipe the viewer can't see right now because it was edited (or rejected) after its last
 * approval. Carries only what is still public: the last approved title. state is always "unavailable"
 * (a pending recipe no longer occurs, since recipes go live when saved).
 */
public record SavedRecipeStubDto(UUID id, String approvedTitle, String state) {
}
