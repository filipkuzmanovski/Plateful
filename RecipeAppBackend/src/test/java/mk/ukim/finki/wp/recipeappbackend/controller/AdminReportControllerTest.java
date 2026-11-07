package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.exception.AdminForbiddenException;
import mk.ukim.finki.wp.recipeappbackend.exception.ReportException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.OpenReportDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.ReportedItemDto;
import mk.ukim.finki.wp.recipeappbackend.security.AdminGuard;
import mk.ukim.finki.wp.recipeappbackend.security.MfaFactorLookup;
import mk.ukim.finki.wp.recipeappbackend.service.AdminReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminReportController.class)
@Import(SecurityConfig.class)
class AdminReportControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AdminReportService reportService;
    @MockitoBean
    AdminGuard adminGuard;
    @MockitoBean
    JwtDecoder jwtDecoder;
    @MockitoBean
    MfaFactorLookup mfaFactorLookup;

    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECIPE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID COMMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("aal", "aal2"));
    }

    @Test
    void queue_returnsThePageWithoutReporterFields() throws Exception {
        OffsetDateTime at = OffsetDateTime.parse("2026-11-01T10:00:00Z");
        var item = new ReportedItemDto("recipe", RECIPE_ID, RECIPE_ID, "Soup", "Al Author", at, 1, false,
                List.of(new OpenReportDto("spam", "ad", at)));
        when(reportService.queue(any())).thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/admin/reports").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].kind").value("recipe"))
                .andExpect(jsonPath("$.content[0].reports[0].reason").value("spam"))
                .andExpect(jsonPath("$.content[0].reports[0].reporterId").doesNotExist())
                .andExpect(jsonPath("$.content[0].reports[0].reporter").doesNotExist());
    }

    @Test
    void count_isAnObjectWithCount() throws Exception {
        when(reportService.count()).thenReturn(5L);

        mockMvc.perform(get("/api/admin/reports/count").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(5));
    }

    @Test
    void dismiss_204_afterTheAdminGuard() throws Exception {
        mockMvc.perform(post("/api/admin/reports/recipes/{id}/dismiss", RECIPE_ID).with(admin()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/admin/reports/comments/{id}/dismiss", COMMENT_ID).with(admin()))
                .andExpect(status().isNoContent());

        var order = inOrder(adminGuard, reportService);
        order.verify(adminGuard).requireAdmin(ADMIN_ID);
        order.verify(reportService).dismissRecipe(RECIPE_ID);
        order.verify(reportService).dismissComment(COMMENT_ID);
    }

    @Test
    void dismiss_needsAdminAndAal2() throws Exception {
        doThrow(AdminForbiddenException.forbidden()).when(adminGuard).requireAdmin(ADMIN_ID);
        mockMvc.perform(post("/api/admin/reports/recipes/{id}/dismiss", RECIPE_ID).with(admin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reports").with(admin())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reports/count").with(admin())).andExpect(status().isForbidden());
        verifyNoInteractions(reportService);

        mockMvc.perform(post("/api/admin/reports/comments/{id}/dismiss", COMMENT_ID)).andExpect(status().isUnauthorized());

        // an admin with a verified factor who is only at aal1 is stopped by the MFA filter
        when(mfaFactorLookup.hasVerifiedFactor(ADMIN_ID)).thenReturn(true);
        mockMvc.perform(post("/api/admin/reports/comments/{id}/dismiss", COMMENT_ID)
                        .with(jwt().jwt(j -> j.subject(ADMIN_ID.toString()).claim("aal", "aal1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        verifyNoInteractions(reportService);
    }

    @Test
    void dismiss_missingIs404_andSecondDismissIs409AlreadyHandled() throws Exception {
        doThrow(new ResourceNotFoundException("Recipe", RECIPE_ID)).when(reportService).dismissRecipe(RECIPE_ID);
        doThrow(ReportException.alreadyHandled()).when(reportService).dismissComment(COMMENT_ID);

        mockMvc.perform(post("/api/admin/reports/recipes/{id}/dismiss", RECIPE_ID).with(admin()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/reports/comments/{id}/dismiss", COMMENT_ID).with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_handled"));
    }
}
