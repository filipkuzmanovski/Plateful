package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.exception.ForbiddenOperationException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeFilter;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RecipeController.class)
@Import(SecurityConfig.class)
class RecipeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RecipeService recipeService;
    @MockitoBean
    JwtDecoder jwtDecoder;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static RequestPostProcessor authed() {
        return jwt().jwt(j -> j.subject(USER_ID.toString()));
    }

    /** Minimal valid recipe payload: title + one ingredient. */
    private static final String VALID_RECIPE_JSON = """
            {"title":"Pancakes",
             "ingredients":[{"ingredientName":"Flour","originalText":"2 cups flour"}]}
            """;

    /** RecipeDisplayDto has 28 components; tests only care about id/title. */
    private static RecipeDisplayDto sampleRecipe(UUID id) {
        return new RecipeDisplayDto(id, "user", null, "Pancakes", null, null, null, null, null,
                null, null, null, List.of(), false, false, false, false, List.of(),
                null, null, null, null, null, List.of(), null, 0L, 0L, null);
    }

    @Test
    void browse_anonymous_is200() throws Exception {
        UUID recipeId = UUID.randomUUID();
        when(recipeService.browse(any(), any()))
                .thenReturn(new PageImpl<>(List.of(sampleRecipe(recipeId))));

        mockMvc.perform(get("/api/recipes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(recipeId.toString()));
    }

    @Test
    void browse_bindsFilterParams() throws Exception {
        when(recipeService.browse(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/recipes?cuisine=italian&vegan=true&maxReadyInMinutes=30&minRating=4"))
                .andExpect(status().isOk());

        ArgumentCaptor<RecipeFilter> captor = ArgumentCaptor.forClass(RecipeFilter.class);
        verify(recipeService).browse(captor.capture(), any());
        RecipeFilter filter = captor.getValue();
        assertThat(filter.cuisine()).isEqualTo("italian");
        assertThat(filter.vegan()).isTrue();
        assertThat(filter.maxReadyInMinutes()).isEqualTo(30);
        assertThat(filter.minRating()).isEqualTo(4.0);
        assertThat(filter.dairyFree()).isNull(); // unset params stay null, not false
    }

    @Test
    void browse_pageSizeIsCappedAt50() throws Exception {
        when(recipeService.browse(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/recipes?size=500"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(recipeService).browse(any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void create_is201WithLocationAndJwtIdentity() throws Exception {
        UUID recipeId = UUID.randomUUID();
        when(recipeService.create(eq(USER_ID), any())).thenReturn(sampleRecipe(recipeId));

        mockMvc.perform(post("/api/recipes")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RECIPE_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/recipes/" + recipeId)))
                .andExpect(jsonPath("$.title").value("Pancakes"));
    }

    @Test
    void create_withoutToken_is401() throws Exception {
        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RECIPE_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_missingIngredients_is400() throws Exception {
        mockMvc.perform(post("/api/recipes")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Pancakes\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ingredients").exists());
    }

    @Test
    void getById_anonymous_is200() throws Exception {
        UUID recipeId = UUID.randomUUID();
        when(recipeService.getById(recipeId)).thenReturn(sampleRecipe(recipeId));

        mockMvc.perform(get("/api/recipes/" + recipeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recipeId.toString()));
    }

    @Test
    void getById_malformedUuid_is400() throws Exception {
        mockMvc.perform(get("/api/recipes/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_notOwner_is403ProblemDetail() throws Exception {
        UUID recipeId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ForbiddenOperationException("You don't own this recipe"))
                .when(recipeService).delete(USER_ID, recipeId);

        mockMvc.perform(delete("/api/recipes/" + recipeId).with(authed()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You don't own this recipe"));
    }

    @Test
    void delete_asOwner_is204() throws Exception {
        UUID recipeId = UUID.randomUUID();

        mockMvc.perform(delete("/api/recipes/" + recipeId).with(authed()))
                .andExpect(status().isNoContent());

        verify(recipeService).delete(USER_ID, recipeId);
    }
}
