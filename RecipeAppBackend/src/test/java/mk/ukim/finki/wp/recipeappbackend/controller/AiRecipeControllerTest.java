package mk.ukim.finki.wp.recipeappbackend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.ai.AiNotConfiguredException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiQuotaExceededException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiUnavailableException;
import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.exception.DailyLimitException;
import mk.ukim.finki.wp.recipeappbackend.exception.GenerationFailedException;
import mk.ukim.finki.wp.recipeappbackend.exception.GenerationRequestException;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidScanPhotoException;
import mk.ukim.finki.wp.recipeappbackend.exception.ScanFailedException;
import mk.ukim.finki.wp.recipeappbackend.exception.ScanLimitException;
import mk.ukim.finki.wp.recipeappbackend.exception.NoPreferencesException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.food.Allergen;
import mk.ukim.finki.wp.recipeappbackend.generation.AiRecipeService;
import mk.ukim.finki.wp.recipeappbackend.generation.AiUsageService;
import mk.ukim.finki.wp.recipeappbackend.generation.PhotoScanService;
import mk.ukim.finki.wp.recipeappbackend.generation.RecipeGenerationService;
import mk.ukim.finki.wp.recipeappbackend.generation.SafetyCheck;
import mk.ukim.finki.wp.recipeappbackend.generation.StoredRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.GenerateRequestDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.AiUsageDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GenerateResultDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeSummaryDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.ScanResultDto;
import mk.ukim.finki.wp.recipeappbackend.security.MfaFactorLookup;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@WebMvcTest(AiRecipeController.class)
@Import(SecurityConfig.class)
class AiRecipeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RecipeGenerationService generation;
    @MockitoBean
    AiRecipeService recipes;
    @MockitoBean
    AiUsageService usage;
    @MockitoBean
    PhotoScanService scans;
    @MockitoBean
    JwtDecoder jwtDecoder; // satisfies SecurityConfig's resource-server wiring
    @MockitoBean
    MfaFactorLookup mfaFactorLookup; // SecurityConfig's MFA filter; the default false keeps most tests unaffected

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECIPE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String BASE = "/api/users/me/ai-recipes";
    private static final String GENERATE_BODY =
            "{\"ingredients\":[\"egg\",\"rice\"],\"mealType\":\"lunch\",\"onlyWhatIHave\":true}";
    private static final GenerateRequestDto GENERATE_REQUEST =
            new GenerateRequestDto(List.of("egg", "rice"), "lunch", true);

    private static final GeneratedRecipeDto RECIPE = new GeneratedRecipeDto(
            RECIPE_ID, "cook", "lunch", "Egg fried rice", "Quick and filling.", 2, 5, 10, "chinese",
            List.of(new StoredRecipe.StoredIngredient("egg", "2", false),
                    new StoredRecipe.StoredIngredient("soy sauce", "1 tbsp", true)),
            List.of("Cook the rice.", "Fry the egg with the rice."),
            new StoredRecipe.Diets(true, false, false, true),
            List.of(new Allergen("egg", "Egg")),
            false, OffsetDateTime.parse("2026-10-31T10:00:00Z"), "groq", null, List.of());

    private static RequestPostProcessor authed() {
        return jwt().jwt(j -> j.subject(USER_ID.toString()).claim("aal", "aal1"));
    }

    // ---- generate

    @Test
    void generate_returnsTheRecipeAndTheRemainingCount() throws Exception {
        when(generation.generate(USER_ID, GENERATE_REQUEST)).thenReturn(new GenerateResultDto(RECIPE, 7));

        mockMvc.perform(post(BASE + "/generate").with(authed())
                        .contentType(MediaType.APPLICATION_JSON).content(GENERATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingToday").value(7))
                .andExpect(jsonPath("$.recipe.id").value(RECIPE_ID.toString()))
                .andExpect(jsonPath("$.recipe.kind").value("cook"))
                .andExpect(jsonPath("$.recipe.mealType").value("lunch"))
                .andExpect(jsonPath("$.recipe.title").value("Egg fried rice"))
                .andExpect(jsonPath("$.recipe.servings").value(2))
                .andExpect(jsonPath("$.recipe.prepMinutes").value(5))
                .andExpect(jsonPath("$.recipe.cookMinutes").value(10))
                .andExpect(jsonPath("$.recipe.cuisine").value("chinese"))
                .andExpect(jsonPath("$.recipe.ingredients[1].name").value("soy sauce"))
                .andExpect(jsonPath("$.recipe.ingredients[1].amount").value("1 tbsp"))
                .andExpect(jsonPath("$.recipe.ingredients[1].extra").value(true))
                .andExpect(jsonPath("$.recipe.steps[0]").value("Cook the rice."))
                .andExpect(jsonPath("$.recipe.diets.vegetarian").value(true))
                .andExpect(jsonPath("$.recipe.diets.glutenFree").value(false))
                .andExpect(jsonPath("$.recipe.allergens[0].key").value("egg"))
                .andExpect(jsonPath("$.recipe.allergens[0].label").value("Egg"))
                .andExpect(jsonPath("$.recipe.saved").value(false))
                .andExpect(jsonPath("$.recipe.provider").value("groq"));
    }

    @Test
    void generate_nullIngredients_is400BeforeTheService() throws Exception {
        mockMvc.perform(post(BASE + "/generate").with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mealType\":\"lunch\",\"onlyWhatIHave\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ingredients").exists());

        verifyNoInteractions(generation);
    }

    @Test
    void generate_blankIngredient_is400BeforeTheService() throws Exception {
        mockMvc.perform(post(BASE + "/generate").with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ingredients\":[\"egg\",\" \"],\"onlyWhatIHave\":false}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(generation);
    }

    @Test
    void generate_invalidRequestWithConflicts_passesTheCodeAndTheList() throws Exception {
        when(generation.generate(USER_ID, GENERATE_REQUEST)).thenThrow(new GenerationRequestException(
                "Some ingredients conflict with your preferences.",
                List.of(new SafetyCheck.Conflict("egg", "allergy: Egg"))));

        mockMvc.perform(post(BASE + "/generate").with(authed())
                        .contentType(MediaType.APPLICATION_JSON).content(GENERATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.conflicts[0].ingredient").value("egg"))
                .andExpect(jsonPath("$.conflicts[0].reason").value("allergy: Egg"));
    }

    @Test
    void generate_noPreferences_is409() throws Exception {
        when(generation.generate(any(), any())).thenThrow(new NoPreferencesException());

        mockMvc.perform(post(BASE + "/generate").with(authed())
                        .contentType(MediaType.APPLICATION_JSON).content(GENERATE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("no_preferences"))
                .andExpect(jsonPath("$.detail").value("Set your food preferences first."));
    }

    @Test
    void generate_dailyLimit_is429() throws Exception {
        when(generation.generate(any(), any())).thenThrow(new DailyLimitException());

        mockMvc.perform(post(BASE + "/generate").with(authed())
                        .contentType(MediaType.APPLICATION_JSON).content(GENERATE_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("daily_limit"))
                .andExpect(jsonPath("$.detail").value("You've used today's 10 AI recipes. More tomorrow."));
    }

    @Test
    void generate_quota_is503() throws Exception {
        when(generation.generate(any(), any())).thenThrow(new AiQuotaExceededException("groq 429"));

        mockMvc.perform(post(BASE + "/generate").with(authed())
                        .contentType(MediaType.APPLICATION_JSON).content(GENERATE_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ai_quota"))
                .andExpect(jsonPath("$.detail").value("The free AI limit is reached for now. Try again later."));
    }

    @Test
    void generate_unavailable_is503() throws Exception {
        when(generation.generate(any(), any())).thenThrow(new AiUnavailableException("timeout"));

        mockMvc.perform(post(BASE + "/generate").with(authed())
                        .contentType(MediaType.APPLICATION_JSON).content(GENERATE_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ai_unavailable"))
                .andExpect(jsonPath("$.detail").value("The AI service isn't responding. Try again in a minute."));
    }

    @Test
    void generate_notConfigured_is503() throws Exception {
        when(generation.generate(any(), any())).thenThrow(new AiNotConfiguredException("no keys"));

        mockMvc.perform(post(BASE + "/generate").with(authed())
                        .contentType(MediaType.APPLICATION_JSON).content(GENERATE_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ai_not_configured"))
                .andExpect(jsonPath("$.detail").value("AI features aren't set up on this server."));
    }

    @Test
    void generate_failedChecks_is422() throws Exception {
        when(generation.generate(any(), any())).thenThrow(new GenerationFailedException());

        mockMvc.perform(post(BASE + "/generate").with(authed())
                        .contentType(MediaType.APPLICATION_JSON).content(GENERATE_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("generation_failed"))
                .andExpect(jsonPath("$.detail")
                        .value("Couldn't make a safe new recipe from these. Try changing the ingredients."));
    }

    @Test
    void generate_withVerifiedFactorAtAal1_is401MfaRequired() throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);

        mockMvc.perform(post(BASE + "/generate").with(authed())
                        .contentType(MediaType.APPLICATION_JSON).content(GENERATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("mfa_required"));

        verifyNoInteractions(generation);
    }

    // ---- scan

    private static MockMultipartFile jpeg(String name, byte... bytes) {
        return new MockMultipartFile("photos", name, "image/jpeg", bytes);
    }

    private static MockMultipartHttpServletRequestBuilder scanOne() {
        return multipart(BASE + "/scan").file(jpeg("a.jpg", (byte) 1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void scan_passesTheBytesInOrderAndReturnsTheResult() throws Exception {
        when(scans.scan(eq(USER_ID), any())).thenReturn(new ScanResultDto(
                List.of("egg", "spinach"),
                List.of(new ScanResultDto.LeftOutDto("peanut butter", "allergy: Peanuts")),
                7));

        mockMvc.perform(multipart(BASE + "/scan")
                        .file(jpeg("a.jpg", (byte) 1, (byte) 2))
                        .file(jpeg("b.jpg", (byte) 3))
                        .with(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ingredients[0]").value("egg"))
                .andExpect(jsonPath("$.ingredients[1]").value("spinach"))
                .andExpect(jsonPath("$.leftOut[0].ingredient").value("peanut butter"))
                .andExpect(jsonPath("$.leftOut[0].reason").value("allergy: Peanuts"))
                .andExpect(jsonPath("$.remainingScans").value(7));

        ArgumentCaptor<List<byte[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(scans).scan(eq(USER_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0)).containsExactly(1, 2);
        assertThat(captor.getValue().get(1)).containsExactly(3);
    }

    @Test
    void scan_missingPart_is400InvalidPhoto() throws Exception {
        mockMvc.perform(multipart(BASE + "/scan").with(authed()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_photo"))
                .andExpect(jsonPath("$.detail").value("Use 1 to 3 photos (JPEG, up to 1 MB each)."));

        verifyNoInteractions(scans);
    }

    @Test
    void scan_nonFileField_is400InvalidPhoto() throws Exception {
        mockMvc.perform(multipart(BASE + "/scan").param("photos", "not a file").with(authed()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_photo"));

        verifyNoInteractions(scans);
    }

    @Test
    void scan_invalidPhoto_is400() throws Exception {
        when(scans.scan(eq(USER_ID), any())).thenThrow(new InvalidScanPhotoException("not a JPEG"));

        mockMvc.perform(scanOne().with(authed()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_photo"))
                .andExpect(jsonPath("$.detail").value("Use 1 to 3 photos (JPEG, up to 1 MB each)."));
    }

    @Test
    void scan_noPreferences_is409() throws Exception {
        when(scans.scan(eq(USER_ID), any())).thenThrow(new NoPreferencesException());

        mockMvc.perform(scanOne().with(authed()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("no_preferences"));
    }

    @Test
    void scan_limit_is429() throws Exception {
        when(scans.scan(eq(USER_ID), any())).thenThrow(new ScanLimitException());

        mockMvc.perform(scanOne().with(authed()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("scan_limit"))
                .andExpect(jsonPath("$.detail").value("You've used today's 10 photo scans. More tomorrow."));
    }

    @Test
    void scan_failed_is422() throws Exception {
        when(scans.scan(eq(USER_ID), any())).thenThrow(new ScanFailedException());

        mockMvc.perform(scanOne().with(authed()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("scan_failed"))
                .andExpect(jsonPath("$.detail")
                        .value("Couldn't read the photo. Try again or type your ingredients."));
    }

    @Test
    void scan_quota_is503() throws Exception {
        when(scans.scan(eq(USER_ID), any())).thenThrow(new AiQuotaExceededException("groq 429"));

        mockMvc.perform(scanOne().with(authed()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ai_quota"));
    }

    @Test
    void scan_unavailable_is503() throws Exception {
        when(scans.scan(eq(USER_ID), any())).thenThrow(new AiUnavailableException("groq down"));

        mockMvc.perform(scanOne().with(authed()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ai_unavailable"));
    }

    @Test
    void scan_notConfigured_is503() throws Exception {
        when(scans.scan(eq(USER_ID), any())).thenThrow(new AiNotConfiguredException("no keys"));

        mockMvc.perform(scanOne().with(authed()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ai_not_configured"));
    }

    @Test
    void scan_uploadTooLarge_is400InvalidPhoto() throws Exception {
        // MockMvc does not enforce the multipart limits, so the exception Spring would raise is thrown here
        when(scans.scan(eq(USER_ID), any())).thenThrow(new MaxUploadSizeExceededException(1));

        mockMvc.perform(scanOne().with(authed()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_photo"))
                .andExpect(jsonPath("$.detail").value("Use 1 to 3 photos (JPEG, up to 1 MB each)."));
    }

    @Test
    void scan_malformedMultipart_is400InvalidPhoto() throws Exception {
        when(scans.scan(eq(USER_ID), any())).thenThrow(new MultipartException("broken"));

        mockMvc.perform(scanOne().with(authed()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_photo"));
    }

    @Test
    void scan_withVerifiedFactorAtAal1_is401MfaRequired() throws Exception {
        when(mfaFactorLookup.hasVerifiedFactor(USER_ID)).thenReturn(true);

        mockMvc.perform(scanOne().with(authed()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("mfa_required"));

        verifyNoInteractions(scans);
    }

    // ---- list

    @Test
    void list_returnsSummariesInThePageShape() throws Exception {
        GeneratedRecipeSummaryDto summary = new GeneratedRecipeSummaryDto(RECIPE_ID, "cook", "lunch",
                "Egg fried rice", "Quick and filling.", 15, OffsetDateTime.parse("2026-10-31T11:00:00Z"));
        when(recipes.saved(eq(USER_ID), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(summary), inv.<Pageable>getArgument(1), 1));

        mockMvc.perform(get(BASE + "?page=0&size=20").with(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(RECIPE_ID.toString()))
                .andExpect(jsonPath("$.content[0].title").value("Egg fried rice"))
                .andExpect(jsonPath("$.content[0].totalMinutes").value(15))
                .andExpect(jsonPath("$.content[0].savedAt").exists())
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void list_capsThePageSizeAtFifty() throws Exception {
        when(recipes.saved(eq(USER_ID), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<GeneratedRecipeSummaryDto>(List.of(), inv.<Pageable>getArgument(1), 0));

        mockMvc.perform(get(BASE + "?size=500").with(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50));
    }

    // ---- usage and get

    @Test
    void usage_returnsUsedLimitAndRemaining() throws Exception {
        when(usage.usage(USER_ID)).thenReturn(new AiUsageDto(3, 10, 7, 4, 10, 6));

        mockMvc.perform(get(BASE + "/usage").with(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(3))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.remaining").value(7))
                .andExpect(jsonPath("$.scansUsed").value(4))
                .andExpect(jsonPath("$.scanLimit").value(10))
                .andExpect(jsonPath("$.scansRemaining").value(6));
    }

    @Test
    void usage_isNotSwallowedByTheIdRoute() throws Exception {
        when(usage.usage(USER_ID)).thenReturn(new AiUsageDto(0, 10, 10, 0, 10, 10));

        mockMvc.perform(get(BASE + "/usage").with(authed()))
                .andExpect(status().isOk());

        verify(usage).usage(USER_ID);
        verifyNoInteractions(recipes);
    }

    @Test
    void get_returnsTheRecipe() throws Exception {
        when(recipes.get(USER_ID, RECIPE_ID)).thenReturn(RECIPE);

        mockMvc.perform(get(BASE + "/" + RECIPE_ID).with(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(RECIPE_ID.toString()))
                .andExpect(jsonPath("$.title").value("Egg fried rice"))
                .andExpect(jsonPath("$.ingredients[0].name").value("egg"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void get_unknownOrForeignId_is404() throws Exception {
        when(recipes.get(USER_ID, RECIPE_ID)).thenThrow(new ResourceNotFoundException("AI recipe", RECIPE_ID));

        mockMvc.perform(get(BASE + "/" + RECIPE_ID).with(authed()))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_nonUuidId_is400() throws Exception {
        mockMvc.perform(get(BASE + "/not-a-uuid").with(authed()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(recipes);
    }

    // ---- save and unsave

    @Test
    void save_is204AndUsesTheJwtUser() throws Exception {
        mockMvc.perform(put(BASE + "/" + RECIPE_ID + "/saved").with(authed()))
                .andExpect(status().isNoContent());

        verify(recipes).save(USER_ID, RECIPE_ID);
    }

    @Test
    void save_unknownId_is404() throws Exception {
        doThrow(new ResourceNotFoundException("AI recipe", RECIPE_ID)).when(recipes).save(USER_ID, RECIPE_ID);

        mockMvc.perform(put(BASE + "/" + RECIPE_ID + "/saved").with(authed()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unsave_is204AndUsesTheJwtUser() throws Exception {
        mockMvc.perform(delete(BASE + "/" + RECIPE_ID + "/saved").with(authed()))
                .andExpect(status().isNoContent());

        verify(recipes).unsave(USER_ID, RECIPE_ID);
    }

    @Test
    void unsave_unknownId_is404() throws Exception {
        doThrow(new ResourceNotFoundException("AI recipe", RECIPE_ID)).when(recipes).unsave(USER_ID, RECIPE_ID);

        mockMvc.perform(delete(BASE + "/" + RECIPE_ID + "/saved").with(authed()))
                .andExpect(status().isNotFound());
    }

    // ---- anonymous

    @Test
    void everyEndpoint_withoutToken_is401() throws Exception {
        mockMvc.perform(post(BASE + "/generate").contentType(MediaType.APPLICATION_JSON).content(GENERATE_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/usage")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/" + RECIPE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(BASE + "/" + RECIPE_ID + "/saved")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BASE + "/" + RECIPE_ID + "/saved")).andExpect(status().isUnauthorized());
        mockMvc.perform(scanOne()).andExpect(status().isUnauthorized());

        verifyNoInteractions(generation, recipes, usage, scans);
    }
}
