package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import java.util.UUID;

/**
 * A saved recipe the viewer can't see right now because it was edited (or rejected) after its last
 * approval. Carries only what is still public: the last approved title. state is "under_review"
 * (pending) or "unavailable" (rejected).
 */
public record SavedRecipeStubDto(UUID id, String approvedTitle, String state) {
}
