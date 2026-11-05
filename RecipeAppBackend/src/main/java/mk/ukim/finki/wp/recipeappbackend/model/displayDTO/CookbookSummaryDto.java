package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A cookbook on the user's shelf. {@code subtitle} is null when the user left it empty. */
public record CookbookSummaryDto(UUID id, int volume, String subtitle, OffsetDateTime createdAt, int recipeCount) {
}
