package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import java.util.List;

public record CuisineInfoDto(String key, String label, String region, List<String> seas, long count) {
}
