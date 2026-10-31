package mk.ukim.finki.wp.recipeappbackend.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.exception.GenerationRequestException;
import mk.ukim.finki.wp.recipeappbackend.generation.DailyMealsService;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.DailyMealsDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's meals for one local day. The browser sends its own local date; it must be a valid ISO date
 * within one day of the server's UTC date (every timezone is within that), else 400 {@code invalid_request}.
 * Identity comes from the JWT only.
 */
@RestController
@RequestMapping("/api/users/me/daily-meals")
public class DailyMealsController {

    static final String BAD_DATE = "The date must be a valid date (yyyy-MM-dd) within one day of today.";

    private final DailyMealsService meals;
    private final Clock clock;

    @Autowired
    public DailyMealsController(DailyMealsService meals) {
        this(meals, Clock.systemUTC());
    }

    DailyMealsController(DailyMealsService meals, Clock clock) {
        this.meals = meals;
        this.clock = clock;
    }

    /** Can take up to a minute for the first request of the day: the AI call happens inside. */
    @GetMapping
    public DailyMealsDto today(@CurrentUserId UUID userId, @RequestParam(required = false) String date) {
        return meals.today(userId, parse(date));
    }

    private LocalDate parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new GenerationRequestException(BAD_DATE);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new GenerationRequestException(BAD_DATE);
        }
        long days = Math.abs(ChronoUnit.DAYS.between(LocalDate.now(clock), date));
        if (days > 1) {
            throw new GenerationRequestException(BAD_DATE);
        }
        return date;
    }
}
