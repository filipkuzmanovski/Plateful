package mk.ukim.finki.wp.recipeappbackend.model.createDTO;

import java.util.List;
import java.util.UUID;

/** What a client sends to make a cookbook. {@code recipeIds} is in chapter order; the service checks both fields. */
public record CookbookCreateDto(List<UUID> recipeIds, String subtitle) {
}
