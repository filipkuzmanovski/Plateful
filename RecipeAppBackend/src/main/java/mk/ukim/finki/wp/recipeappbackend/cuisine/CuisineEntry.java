package mk.ukim.finki.wp.recipeappbackend.cuisine;

import java.util.List;

public record CuisineEntry(String key, String label, Region region, List<String> seas) {
}
