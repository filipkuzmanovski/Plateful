package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.exception.AdminForbiddenException;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidReviewReasonException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.exception.ReviewConflictException;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.security.AdminGuard;
import mk.ukim.finki.wp.recipeappbackend.security.MfaFactorLookup;
import mk.ukim.finki.wp.recipeappbackend.service.AdminReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminReviewController.class)
@Import(SecurityConfig.class)
class AdminReviewControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AdminReviewService adminReviewService;
    @MockitoBean
    AdminGuard adminGuard;
    @MockitoBean
    JwtDecoder jwtDecoder; // satisfies SecurityConfig's resource-server wiring
    @MockitoBean
    MfaFactorLookup mfaFactorLookup; // SecurityConfig's MFA filter; the default false keeps these tests unaffected

    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECIPE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static RequestPostProcessor authedAs(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()));
    }

    @Test
    void anonymousRequestsAre401() throws Exception {
        mockMvc.perform(get("/api/admin/recipes/pending")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/recipes/pending/count")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/recipes/{id}", RECIPE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/recipes/{id}/approve", RECIPE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/recipes/{id}/reject", RECIPE_ID)).andExpect(status().isUnauthorized());
        verifyNoInteractions(adminReviewService, adminGuard);
    }

    @Test
    void nonAdminIs403Forbidden() throws Exception {
        doThrow(AdminForbiddenException.forbidden()).when(adminGuard).requireAdmin(ADMIN_ID);
        String body = "{\"revision\":1,\"reason\":\"Because\"}";

        mockMvc.perform(get("/api/admin/recipes/pending").with(authedAs(ADMIN_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        mockMvc.perform(get("/api/admin/recipes/pending/count").with(authedAs(ADMIN_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        mockMvc.perform(get("/api/admin/recipes/{id}", RECIPE_ID).with(authedAs(ADMIN_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        mockMvc.perform(post("/api/admin/recipes/{id}/approve", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        mockMvc.perform(post("/api/admin/recipes/{id}/reject", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));

        verifyNoInteractions(adminReviewService);
    }

    @Test
    void adminWithoutFactorIs403Setup() throws Exception {
        doThrow(AdminForbiddenException.mfaSetupRequired()).when(adminGuard).requireAdmin(ADMIN_ID);

        mockMvc.perform(get("/api/admin/recipes/pending").with(authedAs(ADMIN_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("admin_mfa_setup_required"));
        mockMvc.perform(post("/api/admin/recipes/{id}/approve", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("admin_mfa_setup_required"));

        verifyNoInteractions(adminReviewService);
    }

    @Test
    void guardRunsBeforeTheService() throws Exception {
        mockMvc.perform(post("/api/admin/recipes/{id}/approve", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2}"))
                .andExpect(status().isNoContent());

        var order = inOrder(adminGuard, adminReviewService);
        order.verify(adminGuard).requireAdmin(ADMIN_ID);
        order.verify(adminReviewService).approve(ADMIN_ID, RECIPE_ID, 2);
    }

    @Test
    void approve204() throws Exception {
        mockMvc.perform(post("/api/admin/recipes/{id}/approve", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":5}"))
                .andExpect(status().isNoContent());

        verify(adminReviewService).approve(ADMIN_ID, RECIPE_ID, 5);
    }

    @Test
    void reject204() throws Exception {
        mockMvc.perform(post("/api/admin/recipes/{id}/reject", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":5,\"reason\":\"Too vague\"}"))
                .andExpect(status().isNoContent());

        verify(adminReviewService).reject(ADMIN_ID, RECIPE_ID, 5, "Too vague");
    }

    @Test
    void missingRevisionIs400() throws Exception {
        mockMvc.perform(post("/api/admin/recipes/{id}/approve", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.revision").exists());

        verify(adminReviewService, never()).approve(any(), any(), anyInt());
    }

    @Test
    void reasonOver500Is400() throws Exception {
        String tooLong = "x".repeat(501);
        doThrow(new InvalidReviewReasonException("The reason must be at most 500 characters"))
                .when(adminReviewService).reject(ADMIN_ID, RECIPE_ID, 1, tooLong);

        mockMvc.perform(post("/api/admin/recipes/{id}/reject", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1,\"reason\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").value("The reason must be at most 500 characters"));
    }

    /** The guard is an interceptor, so it runs before body validation: a non-admin never learns what was wrong with the body. */
    @Test
    void nonAdminInvalidBodyIs403() throws Exception {
        doThrow(AdminForbiddenException.forbidden()).when(adminGuard).requireAdmin(ADMIN_ID);

        mockMvc.perform(post("/api/admin/recipes/{id}/approve", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        mockMvc.perform(post("/api/admin/recipes/{id}/reject", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        mockMvc.perform(post("/api/admin/recipes/{id}/reject", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminReviewService);
    }

    @Test
    void blankReasonIs400() throws Exception {
        doThrow(new InvalidReviewReasonException("A reason is required"))
                .when(adminReviewService).reject(ADMIN_ID, RECIPE_ID, 1, "   ");

        mockMvc.perform(post("/api/admin/recipes/{id}/reject", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1,\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").value("A reason is required"));
    }

    @Test
    void conflictsAre409WithCode() throws Exception {
        doThrow(new ReviewConflictException("not_pending")).when(adminReviewService).approve(ADMIN_ID, RECIPE_ID, 1);
        doThrow(new ReviewConflictException("recipe_changed"))
                .when(adminReviewService).reject(ADMIN_ID, RECIPE_ID, 1, "Reason");

        mockMvc.perform(post("/api/admin/recipes/{id}/approve", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_pending"));
        mockMvc.perform(post("/api/admin/recipes/{id}/reject", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1,\"reason\":\"Reason\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("recipe_changed"));
    }

    @Test
    void unknownRecipeIs404() throws Exception {
        doThrow(new ResourceNotFoundException("Recipe", RECIPE_ID))
                .when(adminReviewService).approve(ADMIN_ID, RECIPE_ID, 1);

        mockMvc.perform(post("/api/admin/recipes/{id}/approve", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pendingCountShape() throws Exception {
        when(adminReviewService.pendingCount()).thenReturn(7L);

        mockMvc.perform(get("/api/admin/recipes/pending/count").with(authedAs(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(7));
    }

    @Test
    void pendingReturnsPageOfRecipes() throws Exception {
        RecipeDisplayDto dto = mock(RecipeDisplayDto.class);
        when(dto.id()).thenReturn(RECIPE_ID);
        Pageable pageable = PageRequest.of(0, 12);
        when(adminReviewService.pending(any())).thenReturn(new PageImpl<>(List.of(dto), pageable, 1));

        mockMvc.perform(get("/api/admin/recipes/pending").with(authedAs(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(RECIPE_ID.toString()));
    }

    @Test
    void getReturnsRecipe() throws Exception {
        RecipeDisplayDto dto = mock(RecipeDisplayDto.class);
        when(dto.id()).thenReturn(RECIPE_ID);
        when(adminReviewService.get(RECIPE_ID)).thenReturn(dto);

        mockMvc.perform(get("/api/admin/recipes/{id}", RECIPE_ID).with(authedAs(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(RECIPE_ID.toString()));
    }
}
