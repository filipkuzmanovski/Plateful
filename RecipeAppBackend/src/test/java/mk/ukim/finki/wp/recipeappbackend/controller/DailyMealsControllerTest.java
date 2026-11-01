package mk.ukim.finki.wp.recipeappbackend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.exception.GenerationRequestException;
import mk.ukim.finki.wp.recipeappbackend.exception.NoPreferencesException;
import mk.ukim.finki.wp.recipeappbackend.generation.DailyMealsService;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.DailyMealsDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeSummaryDto;
import mk.ukim.finki.wp.recipeappbackend.security.MfaFactorLookup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(DailyMealsController.class)
@Import(SecurityConfig.class)
class DailyMealsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DailyMealsService service;
    @MockitoBean
    JwtDecoder jwtDecoder; // satisfies SecurityConfig's resource-server wiring
    @MockitoBean
    MfaFactorLookup mfaFactorLookup; // SecurityConfig's MFA filter; the default false keeps most tests unaffected

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String URL = "/api/users/me/daily-meals";

    private static RequestPostProcessor authed() {
        return jwt().jwt(j -> j.subject(USER_ID.toString()).claim("aal", "aal1"));
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private static GeneratedRecipeSummaryDto summary(String meal, String title) {
        return new GeneratedRecipeSummaryDto(UUID.nameUUIDFromBytes(meal.getBytes()), "daily", meal, title,
                "A tasty dish.", 30, null);
    }

    private static Map<String, GeneratedRecipeSummaryDto> meals() {
        Map<String, GeneratedRecipeSummaryDto> meals = new LinkedHashMap<>();
        meals.put("breakfast", summary("breakfast", "Berry Oat Bowl"));
        meals.put("lunch", summary("lunch", "Lemon Chickpea Salad"));
        meals.put("dinner", summary("dinner", "Garlic Mushroom Risotto"));
        return meals;
    }

    // ---- the JSON for each status

    @Test
    void fresh_returnsReadyWithMeals() throws Exception {
        when(service.today(USER_ID, today())).thenReturn(DailyMealsDto.ready(today(), true, meals()));

        mockMvc.perform(get(URL).param("date", today().toString()).with(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.date").value(today().toString()))
                .andExpect(jsonPath("$.fresh").value(true))
                .andExpect(jsonPath("$.meals.breakfast.title").value("Berry Oat Bowl"))
                .andExpect(jsonPath("$.meals.breakfast.mealType").value("breakfast"))
                .andExpect(jsonPath("$.meals.breakfast.kind").value("daily"))
                .andExpect(jsonPath("$.meals.breakfast.totalMinutes").value(30))
                .andExpect(jsonPath("$.meals.lunch.title").value("Lemon Chickpea Salad"))
                .andExpect(jsonPath("$.meals.dinner.description").value("A tasty dish."));
    }

    @Test
    void fallback_isLabelledWithItsOwnDate() throws Exception {
        LocalDate older = LocalDate.of(2026, 10, 28);
        when(service.today(USER_ID, today())).thenReturn(DailyMealsDto.ready(older, false, meals()));

        mockMvc.perform(get(URL).param("date", today().toString()).with(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.date").value("2026-10-28"))
                .andExpect(jsonPath("$.fresh").value(false))
                .andExpect(jsonPath("$.meals.dinner.title").value("Garlic Mushroom Risotto"));
    }

    @Test
    void generating_hasOnlyTheStatus() throws Exception {
        when(service.today(USER_ID, today())).thenReturn(DailyMealsDto.generating());

        mockMvc.perform(get(URL).param("date", today().toString()).with(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("generating"))
                .andExpect(jsonPath("$.date").doesNotExist())
                .andExpect(jsonPath("$.fresh").doesNotExist())
                .andExpect(jsonPath("$.meals").doesNotExist());
    }

    @Test
    void unavailable_hasOnlyTheStatus() throws Exception {
        when(service.today(USER_ID, today())).thenReturn(DailyMealsDto.unavailable());

        mockMvc.perform(get(URL).param("date", today().toString()).with(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unavailable"))
                .andExpect(jsonPath("$.date").doesNotExist())
                .andExpect(jsonPath("$.fresh").doesNotExist())
                .andExpect(jsonPath("$.meals").doesNotExist());
    }

    @Test
    void date_yesterdayAndTomorrow_areAccepted() throws Exception {
        when(service.today(USER_ID, today().minusDays(1))).thenReturn(DailyMealsDto.generating());
        when(service.today(USER_ID, today().plusDays(1))).thenReturn(DailyMealsDto.generating());

        mockMvc.perform(get(URL).param("date", today().minusDays(1).toString()).with(authed()))
                .andExpect(status().isOk());
        mockMvc.perform(get(URL).param("date", today().plusDays(1).toString()).with(authed()))
                .andExpect(status().isOk());
    }

    // ---- bad dates

    @Test
    void date_twoDaysAway_is400() throws Exception {
        // Five days, so a midnight rollover between the test and the request cannot make it valid.
        for (LocalDate bad : new LocalDate[]{today().plusDays(5), today().minusDays(5)}) {
            mockMvc.perform(get(URL).param("date", bad.toString()).with(authed()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_request"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void date_malformed_is400() throws Exception {
        for (String bad : new String[]{"tomorrow", "2026-13-01", "31-10-2026", "2026-10-31T10:00:00"}) {
            mockMvc.perform(get(URL).param("date", bad).with(authed()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_request"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void date_missing_is400() throws Exception {
        mockMvc.perform(get(URL).with(authed()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        verifyNoInteractions(service);
    }

    // ---- the exact window edge, with a fixed clock (the controller is built by hand, no MockMvc)

    @Test
    void window_isExactlyOneDayEachWayOfTheServersUtcDate() {
        DailyMealsController controller = new DailyMealsController(service,
                Clock.fixed(Instant.parse("2026-11-01T23:59:59Z"), ZoneOffset.UTC));
        DailyMealsDto answer = DailyMealsDto.generating();
        for (String ok : new String[]{"2026-10-31", "2026-11-01", "2026-11-02"}) {
            when(service.today(USER_ID, LocalDate.parse(ok))).thenReturn(answer);
            assertThat(controller.today(USER_ID, ok)).isSameAs(answer);
        }
        for (String bad : new String[]{"2026-10-30", "2026-11-03"}) {
            assertThatThrownBy(() -> controller.today(USER_ID, bad)).isInstanceOf(GenerationRequestException.class);
            verify(service, never()).today(USER_ID, LocalDate.parse(bad));
        }
    }

    // ---- errors and auth

    @Test
    void noPreferences_is409() throws Exception {
        when(service.today(USER_ID, today())).thenThrow(new NoPreferencesException());

        mockMvc.perform(get(URL).param("date", today().toString()).with(authed()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("no_preferences"));
    }

    @Test
    void usesTheCallerFromTheToken() throws Exception {
        when(service.today(USER_ID, today())).thenReturn(DailyMealsDto.unavailable());

        mockMvc.perform(get(URL).param("date", today().toString()).with(authed()))
                .andExpect(status().isOk());

        verify(service).today(USER_ID, today());
    }

    @Test
    void withVerifiedFactorAtAal1_is401MfaRequired() throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);

        mockMvc.perform(get(URL).param("date", today().toString()).with(authed()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("mfa_required"));

        verifyNoInteractions(service);
    }

    @Test
    void withoutToken_is401() throws Exception {
        mockMvc.perform(get(URL).param("date", today().toString()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }
}
