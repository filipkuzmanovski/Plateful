package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.security.MfaFactorLookup;
import mk.ukim.finki.wp.recipeappbackend.service.SavedRecipeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SavedRecipeController.class)
@Import(SecurityConfig.class)
class SavedRecipeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SavedRecipeService savedRecipeService;
    @MockitoBean
    JwtDecoder jwtDecoder; // satisfies SecurityConfig's resource-server wiring
    @MockitoBean
    MfaFactorLookup mfaFactorLookup; // SecurityConfig's MFA filter; the default false keeps these tests unaffected

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RECIPE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static RequestPostProcessor authedAs(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("email", "jane@example.com"));
    }

    @Test
    void anonymousRequestsAre401() throws Exception {
        mockMvc.perform(put("/api/users/me/saved/{id}", RECIPE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/users/me/saved/{id}", RECIPE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me/saved")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me/saved/ids")).andExpect(status().isUnauthorized());
        verifyNoInteractions(savedRecipeService);
    }

    @Test
    void putReturns204AndSavesForCaller() throws Exception {
        mockMvc.perform(put("/api/users/me/saved/{id}", RECIPE_ID).with(authedAs(USER_ID)))
                .andExpect(status().isNoContent());

        verify(savedRecipeService).save(USER_ID, RECIPE_ID);
    }

    @Test
    void putForMissingRecipeIs404() throws Exception {
        doThrow(new ResourceNotFoundException("Recipe", RECIPE_ID))
                .when(savedRecipeService).save(USER_ID, RECIPE_ID);

        mockMvc.perform(put("/api/users/me/saved/{id}", RECIPE_ID).with(authedAs(USER_ID)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/users/me/saved/{id}", RECIPE_ID).with(authedAs(USER_ID)))
                .andExpect(status().isNoContent());

        verify(savedRecipeService).unsave(USER_ID, RECIPE_ID);
    }

    @Test
    void getListReturnsPageOfRecipes() throws Exception {
        RecipeDisplayDto dto = mock(RecipeDisplayDto.class);
        when(dto.id()).thenReturn(RECIPE_ID);
        Pageable pageable = PageRequest.of(0, 12);
        when(savedRecipeService.list(any(), any()))
                .thenReturn(new PageImpl<>(List.of(dto), pageable, 1));

        mockMvc.perform(get("/api/users/me/saved").with(authedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(RECIPE_ID.toString()));
    }

    @Test
    void getIdsReturnsJsonArray() throws Exception {
        when(savedRecipeService.ids(USER_ID)).thenReturn(List.of(RECIPE_ID));

        mockMvc.perform(get("/api/users/me/saved/ids").with(authedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(RECIPE_ID.toString()));
    }

    @Test
    void userIdComesFromToken() throws Exception {
        mockMvc.perform(put("/api/users/me/saved/{id}", RECIPE_ID)
                        .param("userId", OTHER_ID.toString())
                        .with(authedAs(USER_ID)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/users/me/saved/ids")
                        .param("userId", OTHER_ID.toString())
                        .with(authedAs(USER_ID)))
                .andExpect(status().isOk());

        verify(savedRecipeService).save(USER_ID, RECIPE_ID);
        verify(savedRecipeService).ids(USER_ID);
        verify(savedRecipeService, never()).save(OTHER_ID, RECIPE_ID);
        verify(savedRecipeService, never()).ids(OTHER_ID);
    }
}
