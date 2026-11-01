package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import java.util.List;

/**
 * The result of a photo scan: the ingredient names the user can use, those left out because they conflict
 * with the user's food preferences (one row per ingredient and reason), and the scans left today.
 */
public record ScanResultDto(List<String> ingredients, List<LeftOutDto> leftOut, int remainingScans) {

    /** An ingredient found in the photos but left out; {@code reason} is "allergy: X", "diet: X" or "dislike". */
    public record LeftOutDto(String ingredient, String reason) {
    }
}
