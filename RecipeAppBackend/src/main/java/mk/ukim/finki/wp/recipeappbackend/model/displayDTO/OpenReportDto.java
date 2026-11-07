package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import java.time.OffsetDateTime;

/** One open report as an admin sees it. Deliberately has no reporter: who reported stays private. */
public record OpenReportDto(String reason, String note, OffsetDateTime createdAt) {
}
