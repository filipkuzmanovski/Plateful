package mk.ukim.finki.wp.recipeappbackend.model.createDTO;

import java.util.UUID;

/**
 * A report of a recipe or a comment: exactly one of the two ids. The reporter comes from the JWT, never the
 * body. Every rule (one id, one of the five reasons, a note of at most 500 characters) lives in ReportService,
 * so there is one place and one error shape ({@code invalid_report}) for them.
 */
public record ReportCreateDto(UUID recipeId, UUID commentId, String reason, String note) {
}
