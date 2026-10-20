package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.exception.AdminForbiddenException;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidReviewReasonException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.security.AdminGuard;
import mk.ukim.finki.wp.recipeappbackend.security.MfaFactorLookup;
import mk.ukim.finki.wp.recipeappbackend.service.AdminModerationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminModerationController.class)
@Import(SecurityConfig.class)
class AdminModerationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AdminModerationService moderationService;
    @MockitoBean
    AdminGuard adminGuard;
    @MockitoBean
    JwtDecoder jwtDecoder; // satisfies SecurityConfig's resource-server wiring
    @MockitoBean
    MfaFactorLookup mfaFactorLookup; // SecurityConfig's MFA filter; the default false keeps these tests unaffected

    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECIPE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID COMMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static RequestPostProcessor authedAs(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()));
    }

    @Test
    void anonymousIs401() throws Exception {
        mockMvc.perform(post("/api/admin/recipes/{id}/remove", RECIPE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/comments/{id}/remove", COMMENT_ID)).andExpect(status().isUnauthorized());
        verifyNoInteractions(moderationService, adminGuard);
    }

    @Test
    void removeRecipe204() throws Exception {
        mockMvc.perform(post("/api/admin/recipes/{id}/remove", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Spam\"}"))
                .andExpect(status().isNoContent());

        var order = inOrder(adminGuard, moderationService);
        order.verify(adminGuard).requireAdmin(ADMIN_ID);
        order.verify(moderationService).removeRecipe(ADMIN_ID, RECIPE_ID, "Spam");
    }

    @Test
    void removeComment204() throws Exception {
        mockMvc.perform(post("/api/admin/comments/{id}/remove", COMMENT_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Rude\"}"))
                .andExpect(status().isNoContent());

        var order = inOrder(adminGuard, moderationService);
        order.verify(adminGuard).requireAdmin(ADMIN_ID);
        order.verify(moderationService).removeComment(ADMIN_ID, COMMENT_ID, "Rude");
    }

    @Test
    void nonAdminIs403() throws Exception {
        doThrow(AdminForbiddenException.forbidden()).when(adminGuard).requireAdmin(ADMIN_ID);

        mockMvc.perform(post("/api/admin/recipes/{id}/remove", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Spam\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        mockMvc.perform(post("/api/admin/comments/{id}/remove", COMMENT_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Rude\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));

        verifyNoInteractions(moderationService);
    }

    @Test
    void blankReasonIs400UnderErrorsReason() throws Exception {
        doThrow(new InvalidReviewReasonException("A reason is required"))
                .when(moderationService).removeRecipe(ADMIN_ID, RECIPE_ID, "   ");
        doThrow(new InvalidReviewReasonException("A reason is required"))
                .when(moderationService).removeComment(ADMIN_ID, COMMENT_ID, "   ");

        mockMvc.perform(post("/api/admin/recipes/{id}/remove", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").value("A reason is required"));
        mockMvc.perform(post("/api/admin/comments/{id}/remove", COMMENT_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").value("A reason is required"));
    }

    @Test
    void missingReasonReachesTheServiceAsNullSoTheSameRuleApplies() throws Exception {
        doThrow(new InvalidReviewReasonException("A reason is required"))
                .when(moderationService).removeRecipe(ADMIN_ID, RECIPE_ID, null);

        mockMvc.perform(post("/api/admin/recipes/{id}/remove", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").value("A reason is required"));
        verify(moderationService).removeRecipe(ADMIN_ID, RECIPE_ID, null);
    }

    @Test
    void unknownTargetIs404() throws Exception {
        doThrow(new ResourceNotFoundException("Recipe", RECIPE_ID))
                .when(moderationService).removeRecipe(ADMIN_ID, RECIPE_ID, "Spam");
        doThrow(new ResourceNotFoundException("Comment", COMMENT_ID))
                .when(moderationService).removeComment(ADMIN_ID, COMMENT_ID, "Rude");

        mockMvc.perform(post("/api/admin/recipes/{id}/remove", RECIPE_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Spam\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/comments/{id}/remove", COMMENT_ID).with(authedAs(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Rude\"}"))
                .andExpect(status().isNotFound());
    }
}
