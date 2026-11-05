package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.SavedItemDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.SavedRecipeStubDto;
import mk.ukim.finki.wp.recipeappbackend.security.MfaFactorLookup;
import mk.ukim.finki.wp.recipeappbackend.service.LikeService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LikeController.class)
@Import(SecurityConfig.class)
class LikeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LikeService likeService;
    @MockitoBean
    JwtDecoder jwtDecoder; // satisfies SecurityConfig's resource-server wiring
    @MockitoBean
    MfaFactorLookup mfaFactorLookup; // SecurityConfig's MFA filter; the default false keeps these tests unaffected

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECIPE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static RequestPostProcessor authedAs(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("email", "jane@example.com"));
    }

    @Test
    void anonymousRequestsAre401() throws Exception {
        mockMvc.perform(put("/api/users/me/likes/{id}", RECIPE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/users/me/likes/{id}", RECIPE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me/likes")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me/likes/ids")).andExpect(status().isUnauthorized());
        verifyNoInteractions(likeService);
    }

    @Test
    void like_is204ForTheJwtUser() throws Exception {
        mockMvc.perform(put("/api/users/me/likes/{id}", RECIPE_ID).with(authedAs(USER_ID)))
                .andExpect(status().isNoContent());
        verify(likeService).like(USER_ID, RECIPE_ID);
    }

    @Test
    void like_missingRecipe_is404() throws Exception {
        doThrow(new ResourceNotFoundException("Recipe", RECIPE_ID)).when(likeService).like(USER_ID, RECIPE_ID);
        mockMvc.perform(put("/api/users/me/likes/{id}", RECIPE_ID).with(authedAs(USER_ID)))
                .andExpect(status().isNotFound());
    }

    @Test
    void unlike_is204ForTheJwtUser() throws Exception {
        mockMvc.perform(delete("/api/users/me/likes/{id}", RECIPE_ID).with(authedAs(USER_ID)))
                .andExpect(status().isNoContent());
        verify(likeService).unlike(USER_ID, RECIPE_ID);
    }

    @Test
    void list_returnsThePageShape() throws Exception {
        Pageable pageable = PageRequest.of(0, 12);
        SavedItemDto stub = SavedItemDto.ofStub(new SavedRecipeStubDto(RECIPE_ID, "Old title", "under_review"));
        when(likeService.list(any(), any())).thenReturn(new PageImpl<>(List.of(stub), pageable, 1));

        mockMvc.perform(get("/api/users/me/likes").with(authedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].kind").value("stub"))
                .andExpect(jsonPath("$.content[0].stub.approvedTitle").value("Old title"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void ids_returnsTheList() throws Exception {
        when(likeService.ids(USER_ID)).thenReturn(List.of(RECIPE_ID));

        mockMvc.perform(get("/api/users/me/likes/ids").with(authedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(RECIPE_ID.toString()));
    }

    @Test
    void malformedUuid_is400() throws Exception {
        mockMvc.perform(put("/api/users/me/likes/{id}", "not-a-uuid").with(authedAs(USER_ID)))
                .andExpect(status().isBadRequest());
    }
}
