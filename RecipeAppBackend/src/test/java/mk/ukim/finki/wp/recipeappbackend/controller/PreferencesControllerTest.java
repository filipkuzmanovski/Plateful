package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidPreferencesException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.PreferencesDto;
import mk.ukim.finki.wp.recipeappbackend.security.MfaFactorLookup;
import mk.ukim.finki.wp.recipeappbackend.service.PreferencesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PreferencesController.class)
@Import(SecurityConfig.class)
class PreferencesControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PreferencesService preferencesService;
    @MockitoBean
    JwtDecoder jwtDecoder; // satisfies SecurityConfig's resource-server wiring
    @MockitoBean
    MfaFactorLookup mfaFactorLookup; // SecurityConfig's MFA filter; the default false keeps these tests unaffected

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String BODY = """
            {"allergens":["milk"],"diets":["vegan"],"dislikedIngredients":["mushroom"],
             "likedIngredients":["garlic"],"likedCuisines":["italian"]}""";

    private static final PreferencesDto SAMPLE = new PreferencesDto(
            List.of("milk"), List.of("vegan"), List.of("mushroom"), List.of("garlic"), List.of("italian"));

    private static RequestPostProcessor authed() {
        return jwt().jwt(j -> j.subject(USER_ID.toString()).claim("email", "jane@example.com"));
    }

    @Test
    void get_withoutRow_is404() throws Exception {
        when(preferencesService.get(USER_ID))
                .thenThrow(new ResourceNotFoundException("Preferences for user " + USER_ID + " not found"));

        mockMvc.perform(get("/api/users/me/preferences").with(authed()))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_returnsTheFields() throws Exception {
        when(preferencesService.get(USER_ID)).thenReturn(SAMPLE);

        mockMvc.perform(get("/api/users/me/preferences").with(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allergens[0]").value("milk"))
                .andExpect(jsonPath("$.diets[0]").value("vegan"))
                .andExpect(jsonPath("$.dislikedIngredients[0]").value("mushroom"))
                .andExpect(jsonPath("$.likedIngredients[0]").value("garlic"))
                .andExpect(jsonPath("$.likedCuisines[0]").value("italian"));
    }

    @Test
    void put_savesForTheJwtUserAndReturnsTheResult() throws Exception {
        when(preferencesService.save(USER_ID, SAMPLE)).thenReturn(SAMPLE);

        mockMvc.perform(put("/api/users/me/preferences")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allergens[0]").value("milk"))
                .andExpect(jsonPath("$.likedCuisines[0]").value("italian"));

        verify(preferencesService).save(USER_ID, SAMPLE);
    }

    @Test
    void put_invalidPreferences_is400WithDetail() throws Exception {
        when(preferencesService.save(USER_ID, SAMPLE))
                .thenThrow(new InvalidPreferencesException("Unknown allergen: nuts"));

        mockMvc.perform(put("/api/users/me/preferences")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown allergen: nuts"));
    }

    @Test
    void get_withoutToken_is401() throws Exception {
        mockMvc.perform(get("/api/users/me/preferences"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void put_withoutToken_is401() throws Exception {
        mockMvc.perform(put("/api/users/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }
}
