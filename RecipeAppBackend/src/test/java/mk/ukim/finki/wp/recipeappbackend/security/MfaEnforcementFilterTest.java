package mk.ukim.finki.wp.recipeappbackend.security;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.controller.RecipeController;
import mk.ukim.finki.wp.recipeappbackend.controller.SavedRecipeController;
import mk.ukim.finki.wp.recipeappbackend.controller.UserController;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.UserDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeService;
import mk.ukim.finki.wp.recipeappbackend.service.SavedRecipeService;
import mk.ukim.finki.wp.recipeappbackend.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({UserController.class, RecipeController.class, SavedRecipeController.class})
@Import(SecurityConfig.class)
class MfaEnforcementFilterTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ApplicationContext applicationContext;

    @MockitoBean
    UserService userService;
    @MockitoBean
    RecipeService recipeService;
    @MockitoBean
    SavedRecipeService savedRecipeService;
    @MockitoBean
    JwtDecoder jwtDecoder;
    @MockitoBean
    MfaFactorLookup mfaFactorLookup;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String PROFILE_JSON = "{\"firstName\":\"Jane\",\"lastName\":\"Doe\"}";

    private static RequestPostProcessor token(String aal) {
        return jwt().jwt(j -> j.subject(USER_ID.toString()).claim("aal", aal));
    }

    @Test
    void postAal1WithoutFactorPasses() throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(false);
        when(userService.getOrCreate(any(), any(), any()))
                .thenReturn(new UserDisplayDto(USER_ID, "Jane", "Doe"));

        mockMvc.perform(post("/api/users/me").with(token("aal1"))
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void postAal1WithFactorIs401MfaRequired() throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);

        mockMvc.perform(post("/api/users/me").with(token("aal1"))
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("mfa_required"))
                .andExpect(jsonPath("$.detail").value("Two-factor verification required"))
                .andExpect(header().string("WWW-Authenticate", containsString("insufficient_user_authentication")));

        verifyNoInteractions(userService);
    }

    @Test
    void missingAalClaimIsTreatedAsAal1() throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);

        mockMvc.perform(post("/api/users/me")
                        .with(jwt().jwt(j -> j.subject(USER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("mfa_required"));

        verifyNoInteractions(userService);
    }

    @Test
    void postAal2WithFactorPasses() throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);
        when(userService.getOrCreate(any(), any(), any()))
                .thenReturn(new UserDisplayDto(USER_ID, "Jane", "Doe"));

        mockMvc.perform(post("/api/users/me").with(token("aal2"))
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void getMeAal1WithFactorIs401() throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);

        mockMvc.perform(get("/api/users/me").with(token("aal1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("mfa_required"));

        verifyNoInteractions(userService);
    }

    @Test
    void publicGetAal1WithFactorPasses() throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);
        when(recipeService.browse(any(), any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/recipes").with(token("aal1")))
                .andExpect(status().isOk());
    }

    @Test
    void lookupFailureIs503() throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        mockMvc.perform(post("/api/users/me").with(token("aal1"))
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Could not verify two-factor status"));

        verifyNoInteractions(userService);
    }

    @Test
    void anyLookupExceptionIs503() throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID))
                .thenThrow(new IllegalStateException("MFA factor lookup returned no result"));

        mockMvc.perform(post("/api/users/me").with(token("aal1"))
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Could not verify two-factor status"));

        verifyNoInteractions(userService);
    }

    @Test
    void anonymousUnaffected() throws Exception {
        String body = mockMvc.perform(post("/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("mfa_required");
        verifyNoInteractions(mfaFactorLookup);
    }

    /**
     * The raw request URI is not decoded, but Spring routes on the decoded path, so "/m%65" reaches the
     * "/me" handler. The rule must be decided on the decoded path. URI.create is required: a String
     * template would re-encode the percent sign, which the firewall rejects.
     */
    @Test
    void encodedUsersMeIs401() throws Exception {
        assertEncodedMeIsRefused("/api/users/m%65");
    }

    @Test
    void encodedRatingsMeIs401() throws Exception {
        assertEncodedMeIsRefused("/api/recipes/" + UUID.randomUUID() + "/ratings/m%65");
    }

    @Test
    void encodedCommentReactionsMeIs401() throws Exception {
        assertEncodedMeIsRefused("/api/recipes/" + UUID.randomUUID() + "/comments/reactions/m%65");
    }

    @Test
    void getSavedAal1WithFactorIs401() throws Exception {
        assertGetIsRefused("/api/users/me/saved");
    }

    @Test
    void getSavedIdsAal1WithFactorIs401() throws Exception {
        assertGetIsRefused("/api/users/me/saved/ids");
    }

    @Test
    void encodedSavedIs401() throws Exception {
        assertEncodedMeIsRefused("/api/users/m%65/saved");
    }

    @Test
    void getSavedAal2WithFactorPasses() throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);
        when(savedRecipeService.list(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/users/me/saved").with(token("aal2")))
                .andExpect(status().isOk());
    }

    /** Whole-segment comparison: a public user's recipes list is still unguarded for aal1. */
    @Test
    void publicUserRecipesGetAal1WithFactorPasses() throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);
        UUID other = UUID.randomUUID();
        when(recipeService.getByAuthor(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/users/" + other + "/recipes").with(token("aal1")))
                .andExpect(status().isOk());
    }

    private void assertGetIsRefused(String path) throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);

        mockMvc.perform(get(path).with(token("aal1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("mfa_required"));

        verifyNoInteractions(userService, recipeService, savedRecipeService);
    }

    private void assertEncodedMeIsRefused(String encodedPath) throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);

        mockMvc.perform(get(URI.create(encodedPath)).with(token("aal1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("mfa_required"));

        verifyNoInteractions(userService, recipeService, savedRecipeService);
    }

    /**
     * Pins the filter's position in the chain. Every other test uses the jwt() post-processor, which
     * authenticates before the chain runs, so they would still pass if the filter were moved ahead of the
     * BearerTokenAuthenticationFilter. Here a real Authorization header goes through the real bearer filter.
     */
    @Test
    void realBearerTokenIsAuthenticatedBeforeTheMfaFilter() throws Exception {
        Jwt decoded = Jwt.withTokenValue("tok")
                .header("alg", "none")
                .subject(USER_ID.toString())
                .claim("aal", "aal1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode("tok")).thenReturn(decoded);
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);

        mockMvc.perform(post("/api/users/me")
                        .header("Authorization", "Bearer tok")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("mfa_required"))
                .andExpect(header().string("WWW-Authenticate", containsString("insufficient_user_authentication")))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

        verifyNoInteractions(userService);
    }

    /**
     * If the filter were a Spring bean, Boot would also register it as a servlet filter that runs
     * before JWT authentication and marks the request as already filtered, silently disabling it.
     */
    @Test
    void filterIsNotABean() {
        assertThat(applicationContext.getBeansOfType(MfaEnforcementFilter.class)).isEmpty();
    }
}
