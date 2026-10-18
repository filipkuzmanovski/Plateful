package mk.ukim.finki.wp.recipeappbackend.model.createDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A video already uploaded to Supabase Storage: the clip, its still-frame poster and its length.
 * Ownership and format of the paths are checked by RecipeVideoPaths in the service.
 */
public record RecipeVideoCreateDto(
        @NotBlank @Size(max = 200) String path,
        @NotBlank @Size(max = 200) String posterPath,
        @NotNull Integer durationSeconds
) {
}
