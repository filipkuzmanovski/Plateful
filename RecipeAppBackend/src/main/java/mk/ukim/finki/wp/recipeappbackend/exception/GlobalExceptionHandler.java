package mk.ukim.finki.wp.recipeappbackend.exception;

import mk.ukim.finki.wp.recipeappbackend.ai.AiNotConfiguredException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiQuotaExceededException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiUnavailableException;
import mk.ukim.finki.wp.recipeappbackend.cuisine.InvalidCuisineException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.photo.InvalidPhotoException;
import mk.ukim.finki.wp.recipeappbackend.photo.InvalidVideoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single place where failures become RFC 9457 ProblemDetail responses.
 * Extends ResponseEntityExceptionHandler so framework exceptions
 * (malformed JSON -> 400, ResponseStatusException from the @CurrentUserId
 * resolver -> its own status) keep their correct handling instead of being
 * swallowed by the Exception catch-all below.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * A recipe that was approved once but is hidden from this viewer: 404 with a machine-readable code
     * ("under_review" while pending, "unavailable" once rejected) and the last approved title.
     */
    @ExceptionHandler(RecipeHiddenException.class)
    ProblemDetail handleRecipeHidden(RecipeHiddenException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty("code", ex.getStatus() == RecipeStatus.PENDING ? "under_review" : "unavailable");
        problem.setProperty("approvedTitle", ex.getApprovedTitle());
        return problem;
    }

    /** A visible recipe that is not approved cannot take comments, ratings or reactions: 409. */
    @ExceptionHandler(RecipeNotApprovedException.class)
    ProblemDetail handleRecipeNotApproved(RecipeNotApprovedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty("code", "recipe_not_approved");
        return problem;
    }

    /** The admin API refused the caller: 403 with code "forbidden" or "admin_mfa_setup_required". */
    @ExceptionHandler(AdminForbiddenException.class)
    ProblemDetail handleAdminForbidden(AdminForbiddenException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setProperty("code", ex.getCode());
        return problem;
    }

    /** Approve/reject on a recipe that is no longer pending or has been edited since: 409 with code "not_pending" or "recipe_changed". */
    @ExceptionHandler(ReviewConflictException.class)
    ProblemDetail handleReviewConflict(ReviewConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty("code", ex.getCode());
        return problem;
    }

    /** Missing, blank or over-long rejection reason -> 400 in the bean-validation shape. */
    @ExceptionHandler(InvalidReviewReasonException.class)
    ProblemDetail handleInvalidReviewReason(InvalidReviewReasonException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty("errors", Map.of("reason", ex.getMessage()));
        return problem;
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    ProblemDetail handleForbidden(ForbiddenOperationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /** Bad UUID (or other type) in a path/query parameter -> 400, not 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Invalid value for parameter '" + ex.getName() + "'");
    }

    /** Unknown/too many cuisines -> 400 in the same shape as bean-validation failures. */
    @ExceptionHandler(InvalidCuisineException.class)
    ProblemDetail handleInvalidCuisine(InvalidCuisineException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty("errors", Map.of("cuisines", ex.getMessage()));
        return problem;
    }

    /** Unknown allergen or diet, or too many preference entries -> 400 with the message as detail. */
    @ExceptionHandler(InvalidPreferencesException.class)
    ProblemDetail handleInvalidPreferences(InvalidPreferencesException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Unknown sortBy -> 400 in the same shape as bean-validation failures. */
    @ExceptionHandler(InvalidSortException.class)
    ProblemDetail handleInvalidSort(InvalidSortException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty("errors", Map.of("sortBy", ex.getMessage()));
        return problem;
    }

    /** Bad photo paths (foreign folder, duplicates, too many) -> 400 in the bean-validation shape. */
    @ExceptionHandler(InvalidPhotoException.class)
    ProblemDetail handleInvalidPhoto(InvalidPhotoException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty("errors", Map.of("photos", ex.getMessage()));
        return problem;
    }

    /** Bad video paths (foreign folder, wrong type, bad duration) -> 400 in the bean-validation shape. */
    @ExceptionHandler(InvalidVideoException.class)
    ProblemDetail handleInvalidVideo(InvalidVideoException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty("errors", Map.of("video", ex.getMessage()));
        return problem;
    }

    /** A bad generate request (ingredient count, meal type, or conflicts with the user's food preferences): 400. */
    @ExceptionHandler(GenerationRequestException.class)
    ProblemDetail handleGenerationRequest(GenerationRequestException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setProperty("code", "invalid_request");
        if (!ex.getConflicts().isEmpty()) {
            List<Map<String, String>> conflicts = ex.getConflicts().stream()
                    .map(c -> Map.of("ingredient", c.ingredient(), "reason", c.reason()))
                    .toList();
            problem.setProperty("conflicts", conflicts);
        }
        return problem;
    }

    /** No food preferences saved yet: 409. */
    @ExceptionHandler(NoPreferencesException.class)
    ProblemDetail handleNoPreferences(NoPreferencesException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty("code", "no_preferences");
        return problem;
    }

    /** All of today's AI generations used: 429. */
    @ExceptionHandler(DailyLimitException.class)
    ProblemDetail handleDailyLimit(DailyLimitException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setProperty("code", "daily_limit");
        return problem;
    }

    /** Every attempt failed the checks: 422. */
    @ExceptionHandler(GenerationFailedException.class)
    ProblemDetail handleGenerationFailed(GenerationFailedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
        problem.setProperty("code", "generation_failed");
        return problem;
    }

    /** Every AI provider is quota-limited: 503. The exception message is for logs only. */
    @ExceptionHandler(AiQuotaExceededException.class)
    ProblemDetail handleAiQuota(AiQuotaExceededException ex) {
        log.warn("AI quota reached: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The free AI limit is reached for now. Try again later.");
        problem.setProperty("code", "ai_quota");
        return problem;
    }

    /** The AI providers failed in some other way: 503. The exception message is for logs only. */
    @ExceptionHandler(AiUnavailableException.class)
    ProblemDetail handleAiUnavailable(AiUnavailableException ex) {
        log.warn("AI unavailable: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The AI service isn't responding. Try again in a minute.");
        problem.setProperty("code", "ai_unavailable");
        return problem;
    }

    /** No AI key configured: 503. */
    @ExceptionHandler(AiNotConfiguredException.class)
    ProblemDetail handleAiNotConfigured(AiNotConfiguredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "AI features aren't set up on this server.");
        problem.setProperty("code", "ai_not_configured");
        return problem;
    }

    /** Catch-all: log the real cause server-side, leak nothing to the client. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.merge(fe.getField(), fe.getDefaultMessage(), (a, b) -> a + "; " + b);
        }
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }
}
