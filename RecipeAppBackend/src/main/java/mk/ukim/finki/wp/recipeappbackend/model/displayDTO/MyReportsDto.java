package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import java.util.List;
import java.util.UUID;

/** Which of the asked-about items the caller has already reported, open or dismissed. */
public record MyReportsDto(List<UUID> recipeIds, List<UUID> commentIds) {
}
