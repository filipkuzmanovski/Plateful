package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A whole cookbook. Chapters are in order; each is a full recipe, or a stub when it is hidden right now. */
public record CookbookDto(UUID id, int volume, String subtitle, OffsetDateTime createdAt,
                          String ownerFirstName, String ownerLastName, List<SavedItemDto> chapters) {
}
