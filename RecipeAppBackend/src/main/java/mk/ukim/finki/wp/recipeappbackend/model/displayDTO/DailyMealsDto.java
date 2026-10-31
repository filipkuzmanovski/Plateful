package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.Map;

/**
 * Today's meals (spec section 6.1). {@code status} is {@code ready}, {@code generating} or
 * {@code unavailable}; fields that do not apply are left out of the JSON. A fallback is a {@code ready}
 * set with an earlier {@code date} and {@code fresh = false}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DailyMealsDto(String status, LocalDate date, Boolean fresh,
                            Map<String, GeneratedRecipeSummaryDto> meals) {

    public static DailyMealsDto ready(LocalDate date, boolean fresh, Map<String, GeneratedRecipeSummaryDto> meals) {
        return new DailyMealsDto("ready", date, fresh, meals);
    }

    public static DailyMealsDto generating() {
        return new DailyMealsDto("generating", null, null, null);
    }

    public static DailyMealsDto unavailable() {
        return new DailyMealsDto("unavailable", null, null, null);
    }
}
