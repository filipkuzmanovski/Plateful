package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.CommentReactionCreateDto;
import mk.ukim.finki.wp.recipeappbackend.service.ReactionService;
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

@WebMvcTest(ReactionController.class)
@Import(SecurityConfig.class)
class ReactionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ReactionService reactionService;
    @MockitoBean
    JwtDecoder jwtDecoder;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static RequestPostProcessor authed() {
        return jwt().jwt(j -> j.subject(USER_ID.toString()));
    }

    @Test
    void listReactions_anonymous_is200() throws Exception {
        when(reactionService.getForComment(eq(COMMENT_ID), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/comments/" + COMMENT_ID + "/reactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void putMyReaction_upsertsWithJwtIdentity() throws Exception {
        mockMvc.perform(put("/api/comments/" + COMMENT_ID + "/reactions/me")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isLike\":true}"))
                .andExpect(status().isNoContent());

        verify(reactionService).react(USER_ID, COMMENT_ID, new CommentReactionCreateDto(true));
    }

    @Test
    void putMyReaction_withoutToken_is401() throws Exception {
        mockMvc.perform(put("/api/comments/" + COMMENT_ID + "/reactions/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isLike\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putMyReaction_missingIsLike_is400() throws Exception {
        mockMvc.perform(put("/api/comments/" + COMMENT_ID + "/reactions/me")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.isLike").exists());
    }

    @Test
    void deleteMyReaction_is204() throws Exception {
        mockMvc.perform(delete("/api/comments/" + COMMENT_ID + "/reactions/me").with(authed()))
                .andExpect(status().isNoContent());

        verify(reactionService).removeMyReaction(USER_ID, COMMENT_ID);
    }
}
