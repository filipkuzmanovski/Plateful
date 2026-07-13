package mk.ukim.finki.wp.recipeappbackend.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

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
}
