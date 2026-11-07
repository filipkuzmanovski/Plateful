package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.exception.ReportException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.ReportCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.MyReportsDto;
import mk.ukim.finki.wp.recipeappbackend.security.MfaFactorLookup;
import mk.ukim.finki.wp.recipeappbackend.service.ReportService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
@Import(SecurityConfig.class)
class ReportControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ReportService reportService;
    @MockitoBean
    JwtDecoder jwtDecoder; // satisfies SecurityConfig's resource-server wiring
    @MockitoBean
    MfaFactorLookup mfaFactorLookup; // SecurityConfig's MFA filter; the default false keeps most tests unaffected

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECIPE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID COMMENT_ID_2 = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final String RECIPE_BODY =
            "{\"recipeId\":\"" + RECIPE_ID + "\",\"reason\":\"spam\",\"note\":\"Advert\"}";

    private static RequestPostProcessor authed() {
        return jwt().jwt(j -> j.subject(USER_ID.toString()).claim("aal", "aal1"));
    }

    private void postReportExpecting(int status, String code) throws Exception {
        mockMvc.perform(post("/api/reports").with(authed())
                        .contentType(MediaType.APPLICATION_JSON).content(RECIPE_BODY))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void report_is204ForTheJwtUser() throws Exception {
        mockMvc.perform(post("/api/reports").with(authed())
                        .contentType(MediaType.APPLICATION_JSON).content(RECIPE_BODY))
                .andExpect(status().isNoContent());

        verify(reportService).report(USER_ID, new ReportCreateDto(RECIPE_ID, null, "spam", "Advert"));
    }

    @Test
    void report_comment_passesTheCommentId() throws Exception {
        mockMvc.perform(post("/api/reports").with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commentId\":\"" + COMMENT_ID + "\",\"reason\":\"offensive\"}"))
                .andExpect(status().isNoContent());

        verify(reportService).report(USER_ID, new ReportCreateDto(null, COMMENT_ID, "offensive", null));
    }

    @Test
    void anonymousRequestsAre401() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(RECIPE_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/reports/mine").param("recipeId", RECIPE_ID.toString()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reportService);
    }

    @Test
    void report_withVerifiedFactorAtAal1_is401MfaRequired() throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);

        postReportExpecting(401, "mfa_required");

        verifyNoInteractions(reportService);
    }

    @Test
    void invalidReport_is400() throws Exception {
        doThrow(ReportException.invalid("Pick one of the reasons.")).when(reportService).report(any(), any());

        postReportExpecting(400, "invalid_report");
    }

    @Test
    void ownContent_is403() throws Exception {
        doThrow(ReportException.ownContent()).when(reportService).report(any(), any());

        postReportExpecting(403, "own_content");
    }

    @Test
    void alreadyReported_is409WithTheCopy() throws Exception {
        doThrow(ReportException.alreadyReported()).when(reportService).report(any(), any());

        postReportExpecting(409, "already_reported");
        mockMvc.perform(post("/api/reports").with(authed())
                        .contentType(MediaType.APPLICATION_JSON).content(RECIPE_BODY))
                .andExpect(jsonPath("$.detail").value("You've already reported this."));
    }

    @Test
    void reportLimit_is429WithTheCopy() throws Exception {
        doThrow(ReportException.limit()).when(reportService).report(any(), any());

        postReportExpecting(429, "report_limit");
        mockMvc.perform(post("/api/reports").with(authed())
                        .contentType(MediaType.APPLICATION_JSON).content(RECIPE_BODY))
                .andExpect(jsonPath("$.detail").value("You've sent 20 reports today. You can report more tomorrow."));
    }

    @Test
    void missingOrHiddenItem_is404() throws Exception {
        doThrow(new ResourceNotFoundException("Recipe", RECIPE_ID)).when(reportService).report(any(), any());

        mockMvc.perform(post("/api/reports").with(authed())
                        .contentType(MediaType.APPLICATION_JSON).content(RECIPE_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedId_is400() throws Exception {
        mockMvc.perform(post("/api/reports").with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipeId\":\"not-a-uuid\",\"reason\":\"spam\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reportService);
    }

    @Test
    void mine_returnsTheShape() throws Exception {
        when(reportService.mine(USER_ID, RECIPE_ID, List.of(COMMENT_ID, COMMENT_ID_2)))
                .thenReturn(new MyReportsDto(List.of(RECIPE_ID), List.of(COMMENT_ID)));

        mockMvc.perform(get("/api/reports/mine").with(authed())
                        .param("recipeId", RECIPE_ID.toString())
                        .param("commentIds", COMMENT_ID + "," + COMMENT_ID_2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipeIds[0]").value(RECIPE_ID.toString()))
                .andExpect(jsonPath("$.recipeIds.length()").value(1))
                .andExpect(jsonPath("$.commentIds[0]").value(COMMENT_ID.toString()))
                .andExpect(jsonPath("$.commentIds.length()").value(1));
    }

    @Test
    void mine_acceptsRepeatedCommentIds() throws Exception {
        when(reportService.mine(eq(USER_ID), isNull(), eq(List.of(COMMENT_ID, COMMENT_ID_2))))
                .thenReturn(new MyReportsDto(List.of(), List.of()));

        mockMvc.perform(get("/api/reports/mine").with(authed())
                        .param("commentIds", COMMENT_ID.toString())
                        .param("commentIds", COMMENT_ID_2.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipeIds").isArray())
                .andExpect(jsonPath("$.commentIds").isEmpty());
    }

    @Test
    void mine_tooManyCommentIds_is400() throws Exception {
        when(reportService.mine(any(), any(), any())).thenThrow(ReportException.invalid("At most 100 comments."));

        mockMvc.perform(get("/api/reports/mine").with(authed()).param("commentIds", COMMENT_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_report"));
    }

    @Test
    void mine_isAllowedAtAal1EvenWithAFactor() throws Exception {
        // the MFA filter guards writes only
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);
        when(reportService.mine(any(), any(), any())).thenReturn(new MyReportsDto(List.of(), List.of()));

        mockMvc.perform(get("/api/reports/mine").with(authed()).param("recipeId", RECIPE_ID.toString()))
                .andExpect(status().isOk());
    }
}
