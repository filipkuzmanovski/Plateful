package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A reported recipe or comment in the admin queue. {@code kind} is "recipe" or "comment"; {@code recipeId} is
 * the recipe itself for a recipe and the parent recipe for a comment; {@code text} is the title or the body;
 * {@code authorName} is null for an imported recipe. {@code reports} are the open ones, oldest first.
 */
public record ReportedItemDto(String kind,
                              UUID id,
                              UUID recipeId,
                              String text,
                              String authorName,
                              OffsetDateTime postedAt,
                              int openCount,
                              boolean hidden,
                              List<OpenReportDto> reports) {
}
