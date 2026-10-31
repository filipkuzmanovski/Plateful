package mk.ukim.finki.wp.recipeappbackend.model.projection;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One {@code daily_meal_sets} row: the status and the three meal recipe ids (null until ready). */
public record DailySetRow(LocalDate date, String status, UUID breakfastId, UUID lunchId, UUID dinnerId,
                          OffsetDateTime attemptedAt) {
}
