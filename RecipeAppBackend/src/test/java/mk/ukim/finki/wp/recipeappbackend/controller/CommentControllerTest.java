package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.exception.ForbiddenOperationException;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeCommentCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeCommentDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.UserDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.service.CommentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommentController.class)
@Import(SecurityConfig.class)
class CommentControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CommentService commentService;
    @MockitoBean
    JwtDecoder jwtDecoder;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECIPE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static RequestPostProcessor authed() {
        return jwt().jwt(j -> j.subject(USER_ID.toString()));
    }

    private static RecipeCommentDisplayDto sampleComment(String body) {
        return new RecipeCommentDisplayDto(COMMENT_ID,
                new UserDisplayDto(USER_ID, "Jane", "Doe"), body, 0, 0, null);
    }

    @Test
    void listComments_anonymous_is200() throws Exception {
        when(commentService.getForRecipe(eq(RECIPE_ID), any()))
                .thenReturn(new PageImpl<>(List.of(sampleComment("Tasty!"))));

        mockMvc.perform(get("/api/recipes/" + RECIPE_ID + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].body").value("Tasty!"));
    }

    @Test
    void addComment_is201WithJwtIdentity() throws Exception {
        when(commentService.add(eq(USER_ID), eq(RECIPE_ID), any()))
                .thenReturn(sampleComment("Tasty!"));

        mockMvc.perform(post("/api/recipes/" + RECIPE_ID + "/comments")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Tasty!\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("Tasty!"));

        verify(commentService).add(USER_ID, RECIPE_ID, new RecipeCommentCreateDto("Tasty!"));
    }

    @Test
    void addComment_withoutToken_is401() throws Exception {
        mockMvc.perform(post("/api/recipes/" + RECIPE_ID + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Tasty!\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addComment_blankBody_is400() throws Exception {
        mockMvc.perform(post("/api/recipes/" + RECIPE_ID + "/comments")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.body").exists());
    }

    @Test
    void editComment_notAuthor_is403() throws Exception {
        when(commentService.edit(eq(USER_ID), eq(COMMENT_ID), any()))
                .thenThrow(new ForbiddenOperationException("Only the author can edit a comment"));

        mockMvc.perform(put("/api/comments/" + COMMENT_ID)
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"edited\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Only the author can edit a comment"));
    }

    @Test
    void editComment_asAuthor_is200() throws Exception {
        when(commentService.edit(eq(USER_ID), eq(COMMENT_ID), any()))
                .thenReturn(sampleComment("edited"));

        mockMvc.perform(put("/api/comments/" + COMMENT_ID)
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"edited\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("edited"));
    }

    @Test
    void deleteComment_is204() throws Exception {
        mockMvc.perform(delete("/api/comments/" + COMMENT_ID).with(authed()))
                .andExpect(status().isNoContent());

        verify(commentService).delete(USER_ID, COMMENT_ID);
    }

    @Test
    void deleteComment_notAllowed_is403() throws Exception {
        doThrow(new ForbiddenOperationException(
                "Only the comment's author or the recipe's owner can delete it"))
                .when(commentService).delete(USER_ID, COMMENT_ID);

        mockMvc.perform(delete("/api/comments/" + COMMENT_ID).with(authed()))
                .andExpect(status().isForbidden());
    }
}
