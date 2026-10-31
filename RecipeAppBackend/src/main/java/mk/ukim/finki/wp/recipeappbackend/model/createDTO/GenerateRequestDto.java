package mk.ukim.finki.wp.recipeappbackend.model.createDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * "Cook with what I have". {@code mealType} is null or a meal key; the service checks it, and checks the
 * ingredient count after normalising (2 to 15), so the bean-validation cap here is only a sanity bound.
 */
public record GenerateRequestDto(
        @NotNull @Size(max = 30) List<@NotBlank @Size(max = 60) String> ingredients,
        String mealType,
        boolean onlyWhatIHave
) {
}
