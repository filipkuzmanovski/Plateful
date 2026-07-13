package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.UserCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.UserDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeService;
import mk.ukim.finki.wp.recipeappbackend.service.UserService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UserService userService;
    @MockitoBean
    RecipeService recipeService;
    @MockitoBean
    JwtDecoder jwtDecoder; // satisfies SecurityConfig's resource-server wiring

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static RequestPostProcessor authed() {
        return jwt().jwt(j -> j.subject(USER_ID.toString()).claim("email", "jane@example.com"));
    }

    @Test
    void postMe_passesJwtIdentityToService() throws Exception {
        when(userService.getOrCreate(eq(USER_ID), eq("jane@example.com"), any()))
                .thenReturn(new UserDisplayDto(USER_ID, "Jane", "Doe"));

        mockMvc.perform(post("/api/users/me")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Jane\",\"lastName\":\"Doe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.firstName").value("Jane"));

        // identity came from the JWT, names from the body — the core contract
        verify(userService).getOrCreate(USER_ID, "jane@example.com", new UserCreateDto("Jane", "Doe"));
    }

    @Test
    void postMe_withoutToken_is401() throws Exception {
        mockMvc.perform(post("/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Jane\",\"lastName\":\"Doe\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postMe_blankFirstName_is400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/users/me")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"\",\"lastName\":\"Doe\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.firstName").exists());
    }

    @Test
    void getMe_notProvisioned_is404ProblemDetail() throws Exception {
        when(userService.getById(USER_ID)).thenThrow(new ResourceNotFoundException("User", USER_ID));

        mockMvc.perform(get("/api/users/me").with(authed()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("User with id " + USER_ID + " not found"));
    }

    @Test
    void getMe_withoutToken_is401() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putMe_updatesNames() throws Exception {
        when(userService.updateNames(USER_ID, "Janet", "Doe"))
                .thenReturn(new UserDisplayDto(USER_ID, "Janet", "Doe"));

        mockMvc.perform(put("/api/users/me")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Janet\",\"lastName\":\"Doe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Janet"));
    }

    @Test
    void getPublicProfile_anonymous_is200() throws Exception {
        UUID otherId = UUID.randomUUID();
        when(userService.getById(otherId)).thenReturn(new UserDisplayDto(otherId, "Bob", "Smith"));

        mockMvc.perform(get("/api/users/" + otherId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Bob"));
    }

    @Test
    void getUserRecipes_anonymous_is200() throws Exception {
        UUID otherId = UUID.randomUUID();
        when(recipeService.getByAuthor(eq(otherId), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/users/" + otherId + "/recipes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }
}
