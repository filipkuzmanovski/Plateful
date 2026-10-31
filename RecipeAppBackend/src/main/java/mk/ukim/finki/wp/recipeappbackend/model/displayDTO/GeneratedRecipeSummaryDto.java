package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A generated recipe in a list: no ingredients or steps. {@code savedAt} is null when not saved. */
public record GeneratedRecipeSummaryDto(
        UUID id,
        String kind,
        String mealType,
        String title,
        String description,
        int totalMinutes,
        OffsetDateTime savedAt
) {
}
