package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeRatingCreateDto;
import mk.ukim.finki.wp.recipeappbackend.service.RatingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RatingController.class)
@Import(SecurityConfig.class)
class RatingControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RatingService ratingService;
    @MockitoBean
    JwtDecoder jwtDecoder;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECIPE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static RequestPostProcessor authed() {
        return jwt().jwt(j -> j.subject(USER_ID.toString()));
    }

    @Test
    void listRatings_anonymous_is200() throws Exception {
        when(ratingService.getForRecipe(eq(RECIPE_ID), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/recipes/" + RECIPE_ID + "/ratings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void putMyRating_upsertsWithJwtIdentity() throws Exception {
        mockMvc.perform(put("/api/recipes/" + RECIPE_ID + "/ratings/me")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4}"))
                .andExpect(status().isNoContent());

        verify(ratingService).rate(USER_ID, RECIPE_ID, new RecipeRatingCreateDto(4));
    }

    @Test
    void putMyRating_withoutToken_is401() throws Exception {
        mockMvc.perform(put("/api/recipes/" + RECIPE_ID + "/ratings/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putMyRating_outOfRange_is400() throws Exception {
        mockMvc.perform(put("/api/recipes/" + RECIPE_ID + "/ratings/me")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.rating").exists());
    }

    @Test
    void getMyRating_returnsRating() throws Exception {
        when(ratingService.getMyRating(USER_ID, RECIPE_ID)).thenReturn(Optional.of(5));

        mockMvc.perform(get("/api/recipes/" + RECIPE_ID + "/ratings/me").with(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5));
    }

    @Test
    void getMyRating_none_is404() throws Exception {
        when(ratingService.getMyRating(USER_ID, RECIPE_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/recipes/" + RECIPE_ID + "/ratings/me").with(authed()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMyRating_anonymous_is401() throws Exception {
        mockMvc.perform(get("/api/recipes/" + RECIPE_ID + "/ratings/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteMyRating_is204() throws Exception {
        mockMvc.perform(delete("/api/recipes/" + RECIPE_ID + "/ratings/me").with(authed()))
                .andExpect(status().isNoContent());

        verify(ratingService).deleteMyRating(USER_ID, RECIPE_ID);
    }
}
