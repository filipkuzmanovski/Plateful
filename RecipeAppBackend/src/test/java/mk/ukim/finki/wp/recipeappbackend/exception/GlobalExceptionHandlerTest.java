package mk.ukim.finki.wp.recipeappbackend.exception;

import mk.ukim.finki.wp.recipeappbackend.ai.AiNotConfiguredException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiQuotaExceededException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiUnavailableException;
import mk.ukim.finki.wp.recipeappbackend.generation.SafetyCheck;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void notFoundMapsTo404WithMessage() {
        UUID id = UUID.randomUUID();
        ProblemDetail problem = handler.handleNotFound(new ResourceNotFoundException("Recipe", id));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getDetail()).isEqualTo("Recipe with id " + id + " not found");
    }

    @Test
    void forbiddenMapsTo403WithMessage() {
        ProblemDetail problem =
                handler.handleForbidden(new ForbiddenOperationException("You don't own this recipe"));

        assertThat(problem.getStatus()).isEqualTo(403);
        assertThat(problem.getDetail()).isEqualTo("You don't own this recipe");
    }

    @Test
    void unexpectedExceptionMapsTo500WithGenericDetail() {
        ProblemDetail problem = handler.handleUnexpected(new IllegalStateException("secret internals"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getDetail()).doesNotContain("secret internals");
    }

    @Test
    void validationFailureListsFieldErrors() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "userCreateDto");
        binding.addError(new FieldError("userCreateDto", "firstName", "must not be blank"));
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(mock(org.springframework.core.MethodParameter.class), binding);

        ResponseEntity<Object> response =
                handler.handleMethodArgumentNotValid(ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem.getProperties())
                .containsEntry("errors", Map.of("firstName", "must not be blank"));
    }

    @Test
    void invalidCuisineMapsTo400WithCuisinesFieldError() {
        ProblemDetail problem = handler.handleInvalidCuisine(
                new mk.ukim.finki.wp.recipeappbackend.cuisine.InvalidCuisineException(java.util.List.of("lol")));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Validation failed");
        assertThat(problem.getProperties())
                .containsEntry("errors", Map.of("cuisines", "Unknown cuisine: lol"));
    }

    @Test
    void invalidPhotoMapsTo400WithPhotosFieldError() {
        ProblemDetail problem = handler.handleInvalidPhoto(
                new mk.ukim.finki.wp.recipeappbackend.photo.InvalidPhotoException("At most 6 photos"));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Validation failed");
        assertThat(problem.getProperties())
                .containsEntry("errors", Map.of("photos", "At most 6 photos"));
    }

    @Test
    void invalidVideoMapsTo400WithVideoFieldError() {
        ProblemDetail problem = handler.handleInvalidVideo(
                new mk.ukim.finki.wp.recipeappbackend.photo.InvalidVideoException("Videos can be up to 60 seconds long."));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Validation failed");
        assertThat(problem.getProperties())
                .containsEntry("errors", Map.of("video", "Videos can be up to 60 seconds long."));
    }

    @Test
    void hiddenPendingMapsTo404UnderReviewWithApprovedTitle() {
        ProblemDetail problem = handler.handleRecipeHidden(
                new RecipeHiddenException(UUID.randomUUID(), "Old Title", RecipeStatus.PENDING));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getProperties())
                .containsEntry("code", "under_review")
                .containsEntry("approvedTitle", "Old Title");
    }

    @Test
    void hiddenRejectedMapsTo404UnavailableWithApprovedTitle() {
        ProblemDetail problem = handler.handleRecipeHidden(
                new RecipeHiddenException(UUID.randomUUID(), "Old Title", RecipeStatus.REJECTED));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getProperties())
                .containsEntry("code", "unavailable")
                .containsEntry("approvedTitle", "Old Title");
    }

    @Test
    void recipeNotApprovedMapsTo409WithCode() {
        ProblemDetail problem = handler.handleRecipeNotApproved(
                new RecipeNotApprovedException(UUID.randomUUID(), RecipeStatus.PENDING));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "recipe_not_approved");
    }

    @Test
    void invalidSortMapsTo400WithSortByFieldError() {
        ProblemDetail problem = handler.handleInvalidSort(new InvalidSortException("Unknown sort: bogus"));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Validation failed");
        assertThat(problem.getProperties())
                .containsEntry("errors", Map.of("sortBy", "Unknown sort: bogus"));
    }

    @Test
    void generationRequestMapsTo400InvalidRequestWithConflicts() {
        ProblemDetail problem = handler.handleGenerationRequest(new GenerationRequestException(
                "Some ingredients conflict with your food preferences.",
                List.of(new SafetyCheck.Conflict("Peanut butter", "allergy: Peanuts"),
                        new SafetyCheck.Conflict("green peas", "dislike"))));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Some ingredients conflict with your food preferences.");
        assertThat(problem.getProperties()).containsEntry("code", "invalid_request");
        assertThat(problem.getProperties()).containsEntry("conflicts", List.of(
                Map.of("ingredient", "Peanut butter", "reason", "allergy: Peanuts"),
                Map.of("ingredient", "green peas", "reason", "dislike")));
    }

    @Test
    void conflictsAreSerialisedAsIngredientAndReason() throws Exception {
        ProblemDetail problem = handler.handleGenerationRequest(new GenerationRequestException(
                "Some ingredients conflict with your food preferences.",
                List.of(new SafetyCheck.Conflict("Peanut butter", "allergy: Peanuts"))));

        tools.jackson.databind.JsonNode json = tools.jackson.databind.json.JsonMapper.builder().build()
                .valueToTree(Map.of("conflicts", problem.getProperties().get("conflicts")));

        assertThat(json.get("conflicts").get(0).get("ingredient").asString()).isEqualTo("Peanut butter");
        assertThat(json.get("conflicts").get(0).get("reason").asString()).isEqualTo("allergy: Peanuts");
    }

    @Test
    void generationRequestWithoutConflictsHasNoConflictsProperty() {
        ProblemDetail problem = handler.handleGenerationRequest(
                new GenerationRequestException("Choose between 2 and 15 different ingredients."));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Choose between 2 and 15 different ingredients.");
        assertThat(problem.getProperties()).containsEntry("code", "invalid_request");
        assertThat(problem.getProperties()).doesNotContainKey("conflicts");
    }

    @Test
    void noPreferencesMapsTo409() {
        ProblemDetail problem = handler.handleNoPreferences(new NoPreferencesException());

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getDetail()).isEqualTo("Set your food preferences first.");
        assertThat(problem.getProperties()).containsEntry("code", "no_preferences");
    }

    @Test
    void dailyLimitMapsTo429() {
        ProblemDetail problem = handler.handleDailyLimit(new DailyLimitException());

        assertThat(problem.getStatus()).isEqualTo(429);
        assertThat(problem.getDetail()).isEqualTo("You've used today's 10 AI recipes. More tomorrow.");
        assertThat(problem.getProperties()).containsEntry("code", "daily_limit");
    }

    @Test
    void generationFailedMapsTo422() {
        ProblemDetail problem = handler.handleGenerationFailed(new GenerationFailedException());

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getDetail())
                .isEqualTo("Couldn't make a safe new recipe from these. Try changing the ingredients.");
        assertThat(problem.getProperties()).containsEntry("code", "generation_failed");
    }

    @Test
    void aiQuotaMapsTo503AndKeepsTheInternalMessageOut() {
        ProblemDetail problem = handler.handleAiQuota(new AiQuotaExceededException("groq 429"));

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getDetail()).isEqualTo("The free AI limit is reached for now. Try again later.");
        assertThat(problem.getProperties()).containsEntry("code", "ai_quota");
    }

    @Test
    void aiUnavailableMapsTo503AndKeepsTheInternalMessageOut() {
        ProblemDetail problem = handler.handleAiUnavailable(new AiUnavailableException("groq HTTP 500"));

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getDetail()).isEqualTo("The AI service isn't responding. Try again in a minute.");
        assertThat(problem.getProperties()).containsEntry("code", "ai_unavailable");
    }

    @Test
    void aiNotConfiguredMapsTo503() {
        ProblemDetail problem = handler.handleAiNotConfigured(new AiNotConfiguredException("no key"));

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getDetail()).isEqualTo("AI features aren't set up on this server.");
        assertThat(problem.getProperties()).containsEntry("code", "ai_not_configured");
    }
}
