package mk.ukim.finki.wp.recipeappbackend.model.createDTO;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecipeCreateDtoServingsTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static RecipeCreateDto dto(Integer servings) {
        return new RecipeCreateDto("Soup", servings, null, null, null, null, null, false, false, false, false,
                null, null, List.of(new RecipeIngredientCreateDto("salt", "1 tsp salt")), null);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void servingsBelowOneIsRejected(Integer servings) {
        assertThat(validator.validateProperty(dto(servings), "servings"))
                .extracting(ConstraintViolation::getMessage)
                .contains("Enter 1 or more servings");
    }

    @Test
    void oneServingIsAccepted() {
        assertThat(validator.validateProperty(dto(1), "servings")).isEmpty();
    }
}
