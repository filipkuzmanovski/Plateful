# Backend REST API + Auth Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the existing service layer of `RecipeAppBackend` as a REST API with Supabase-JWT identity, public reads / authenticated writes, ProblemDetail errors, CORS, and controller slice tests.

**Architecture:** Five thin controllers (users, recipes, ratings, comments, reactions) delegate to the existing services. A `@CurrentUserId` argument resolver converts the JWT `sub` claim to a `UUID` parameter. `SecurityConfig` decides public vs. authenticated per route; ownership checks stay in the services. One `@RestControllerAdvice` maps all failures to RFC 9457 ProblemDetail.

**Tech Stack:** Spring Boot 4.1.0, Java 21, Spring Security OAuth2 resource server (Supabase JWKS, ES256), Spring Data JPA (services already exist), JUnit 5 + `@WebMvcTest` + spring-security-test.

**Spec:** `docs/superpowers/specs/2026-07-13-backend-api-auth-design.md`

## Global Constraints

- All routes under `/api`. Identity (user id, email) ALWAYS comes from the verified JWT (`sub`, `email` claims) — never from a request body or path.
- Ownership/authorization checks live in the services (already implemented). Controllers never repeat them. `SecurityConfig` only decides authenticated vs. public.
- Errors are RFC 9457 ProblemDetail (`application/problem+json`).
- Pagination: Spring `Pageable` (`page`, `size`), `size` capped at 50 via `spring.data.web.pageable.max-page-size=50`.
- CORS: property `app.cors.allowed-origins`, default `http://localhost:5173`.
- Base package: `mk.ukim.finki.wp.recipeappbackend`. Source root: `RecipeAppBackend/src/main/java/mk/ukim/finki/wp/recipeappbackend/`, test root: `RecipeAppBackend/src/test/java/mk/ukim/finki/wp/recipeappbackend/`.
- Run ALL commands from the `RecipeAppBackend` directory in PowerShell. Test command shape: `.\mvnw.cmd test "-Dtest=ClassName"`.
- `RecipeAppBackendApplicationTests` (context load) needs a real DB connection (`SUPABASE_DB_PASSWORD` env var). Slice tests do NOT. Never run the full unfiltered suite unless that env var is set in your shell.
- Boot 4 notes: mock beans with `@MockitoBean` (`org.springframework.test.context.bean.override.mockito.MockitoBean`) — `@MockBean` no longer exists. `@WebMvcTest` lives at `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`; if that import doesn't resolve, use the legacy `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest`.
- Commit after every task. Nothing in `RecipeAppBackend/` is committed yet — the first commit that touches it brings the whole backend tree in; that is expected and fine (secrets are already externalized; `.idea/workspace.xml` is gitignored).

---

### Task 1: `@CurrentUserId` argument resolver

**Files:**
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/security/CurrentUserId.java`
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/security/CurrentUserIdArgumentResolver.java`
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/config/WebConfig.java`
- Test: `src/test/java/mk/ukim/finki/wp/recipeappbackend/security/CurrentUserIdArgumentResolverTest.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `@CurrentUserId UUID userId` — a parameter annotation controllers (Tasks 4–8) put on a `UUID` handler parameter to receive the authenticated user's id. Throws `ResponseStatusException(401)` when there is no JWT principal or `sub` is not a UUID. Also produces `WebConfig`, which registers the resolver and sets Page serialization to the stable `VIA_DTO` mode (page JSON shape: `{"content":[...],"page":{"size":..,"number":..,"totalElements":..,"totalPages":..}}`).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mk/ukim/finki/wp/recipeappbackend/security/CurrentUserIdArgumentResolverTest.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserIdArgumentResolverTest {

    private final CurrentUserIdArgumentResolver resolver = new CurrentUserIdArgumentResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateWithSub(String sub) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "ES256")
                .subject(sub)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void resolvesUuidFromJwtSubject() {
        UUID id = UUID.randomUUID();
        authenticateWithSub(id.toString());

        Object result = resolver.resolveArgument(null, null, null, null);

        assertThat(result).isEqualTo(id);
    }

    @Test
    void nonUuidSubjectThrows401() {
        authenticateWithSub("not-a-uuid");

        assertThatThrownBy(() -> resolver.resolveArgument(null, null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void missingAuthenticationThrows401() {
        // no authentication set at all
        assertThatThrownBy(() -> resolver.resolveArgument(null, null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=CurrentUserIdArgumentResolverTest"`
Expected: BUILD FAILURE — compilation error, `cannot find symbol: class CurrentUserIdArgumentResolver`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/security/CurrentUserId.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the authenticated user's id (JWT "sub" claim) as a UUID handler
 * parameter. The ONLY sanctioned way for controllers to learn who is calling
 * — identity never comes from a request body or path variable.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {
}
```

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/security/CurrentUserIdArgumentResolver.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.security;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Resolves @CurrentUserId UUID parameters from the JWT principal.
 * Supabase always issues UUID subjects, so a non-UUID sub means the token
 * wasn't minted for this app — reject as 401, never let it surface as a 500.
 */
public class CurrentUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && UUID.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT subject is not a valid user id");
        }
    }
}
```

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/config/WebConfig.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.config;

import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserIdArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

/**
 * VIA_DTO pins the Page JSON contract to the stable shape
 * {"content":[...],"page":{size,number,totalElements,totalPages}} instead of
 * serializing PageImpl internals (which Spring warns may change release to
 * release). The frontend codes against this shape.
 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserIdArgumentResolver());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=CurrentUserIdArgumentResolverTest"`
Expected: `Tests run: 3, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/mk/ukim/finki/wp/recipeappbackend/security src/main/java/mk/ukim/finki/wp/recipeappbackend/config/WebConfig.java src/test/java/mk/ukim/finki/wp/recipeappbackend/security
git commit -m "feat: add @CurrentUserId JWT argument resolver and web config"
```

---

### Task 2: Global exception handler (ProblemDetail)

**Files:**
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/mk/ukim/finki/wp/recipeappbackend/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: existing `ResourceNotFoundException`, `ForbiddenOperationException` (package `...exception`).
- Produces: every controller error becomes `application/problem+json`. Extending `ResponseEntityExceptionHandler` gives 400 for malformed bodies (`HttpMessageNotReadableException`) and 401-preserving handling of `ResponseStatusException` (thrown by the Task 1 resolver) for free. Validation failures carry an `errors` extension property: `{"<field>": "<message>"}`. Tasks 4–8 slice tests assert on `$.detail` and `$.errors.*`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mk/ukim/finki/wp/recipeappbackend/exception/GlobalExceptionHandlerTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=GlobalExceptionHandlerTest"`
Expected: BUILD FAILURE — compilation error, `cannot find symbol: class GlobalExceptionHandler`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/exception/GlobalExceptionHandler.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.exception;

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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=GlobalExceptionHandlerTest"`
Expected: `Tests run: 4, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/mk/ukim/finki/wp/recipeappbackend/exception/GlobalExceptionHandler.java src/test/java/mk/ukim/finki/wp/recipeappbackend/exception
git commit -m "feat: add ProblemDetail global exception handler"
```

---

### Task 3: SecurityConfig — public reads, CORS, properties; delete TestController

**Files:**
- Modify: `src/main/java/mk/ukim/finki/wp/recipeappbackend/config/SecurityConfig.java` (full replacement below)
- Modify: `src/main/resources/application.properties` (append two properties)
- Delete: `src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/TestController.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: route security rules all later tasks' tests rely on — anonymous `GET` allowed on `/api/recipes/**` (except `/api/recipes/*/ratings/me`), `/api/users/*`, `/api/users/*/recipes`, `/api/comments/*/reactions`; everything else 401 without a JWT. Also `CorsConfigurationSource` driven by `app.cors.allowed-origins`. Slice tests must `@Import(SecurityConfig.class)` and `@MockitoBean JwtDecoder jwtDecoder`.

No isolated test cycle here — the rules are behaviorally verified by every slice test in Tasks 4–8 (each has anonymous-200 and anonymous-401 cases). This task only needs to compile.

- [ ] **Step 1: Replace SecurityConfig**

Replace the entire content of `src/main/java/mk/ukim/finki/wp/recipeappbackend/config/SecurityConfig.java` with:

```java
package mk.ukim.finki.wp.recipeappbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Route-level security only: which routes are public vs. need a valid
 * Supabase JWT. OWNERSHIP checks (may this user edit THIS recipe?) live in
 * the services — the backend's DB connection bypasses RLS, so those service
 * checks are the only per-row authorization layer.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final List<String> allowedOrigins;

    public SecurityConfig(@Value("${app.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable()) // stateless API, no cookies/forms involved
                .cors(Customizer.withDefaults())
                // JWT-only API: never create or read an HTTP session.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // "me" routes are authenticated and MUST be declared
                        // before the broader public GET patterns that would
                        // otherwise match them.
                        .requestMatchers(HttpMethod.GET,
                                "/api/users/me",
                                "/api/recipes/*/ratings/me").authenticated()
                        // Public reads: browsing content needs no account.
                        .requestMatchers(HttpMethod.GET,
                                "/api/recipes/**",
                                "/api/users/*",
                                "/api/users/*/recipes",
                                "/api/comments/*/reactions").permitAll()
                        // Deny-by-default: every mutation and unknown route.
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
```

- [ ] **Step 2: Append properties**

Append to `src/main/resources/application.properties`:

```properties

# Frontend origins allowed to call this API from a browser (comma-separated).
app.cors.allowed-origins=http://localhost:5173

# Hard cap on ?size= for all paginated endpoints.
spring.data.web.pageable.max-page-size=50
```

- [ ] **Step 3: Delete TestController**

```powershell
Remove-Item src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/TestController.java
```

- [ ] **Step 4: Verify it compiles**

Run: `.\mvnw.cmd -q compile`
Expected: BUILD SUCCESS, no output errors.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/mk/ukim/finki/wp/recipeappbackend/config/SecurityConfig.java src/main/resources/application.properties src/main/java/mk/ukim/finki/wp/recipeappbackend/controller
git commit -m "feat: public-read/auth-write security rules, CORS, page size cap"
```

---

### Task 4: UserController

**Files:**
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/UserController.java`
- Test: `src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/UserControllerTest.java`

**Interfaces:**
- Consumes: `UserService.getOrCreate(UUID, String, UserCreateDto)`, `UserService.getById(UUID)`, `UserService.updateNames(UUID, String, String)` → `UserDisplayDto(UUID id, String firstName, String lastName)`; `RecipeService.getByAuthor(UUID, Pageable)` → `Page<RecipeDisplayDto>`; `@CurrentUserId` (Task 1).
- Produces: routes `POST|GET|PUT /api/users/me`, `GET /api/users/{id}`, `GET /api/users/{id}/recipes`. Test file also establishes the shared test idioms (jwt() post-processor, `@MockitoBean JwtDecoder`) reused verbatim in Tasks 5–8.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/UserControllerTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=UserControllerTest"`
Expected: BUILD FAILURE — compilation error, `cannot find symbol: class UserController`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/UserController.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.controller;

import jakarta.validation.Valid;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.UserCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.UserDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeService;
import mk.ukim.finki.wp.recipeappbackend.service.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final RecipeService recipeService;

    public UserController(UserService userService, RecipeService recipeService) {
        this.userService = userService;
        this.recipeService = recipeService;
    }

    /**
     * Get-or-create my profile after Supabase signup. Idempotent — safe to
     * retry, returns the existing profile if it's already there. id and
     * email come from the verified JWT, never the body.
     */
    @PostMapping("/me")
    public UserDisplayDto createMyProfile(@CurrentUserId UUID userId,
                                          @AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody UserCreateDto dto) {
        return userService.getOrCreate(userId, jwt.getClaimAsString("email"), dto);
    }

    /** 404 here = "profile not provisioned yet" — the frontend's cue to collect names. */
    @GetMapping("/me")
    public UserDisplayDto myProfile(@CurrentUserId UUID userId) {
        return userService.getById(userId);
    }

    @PutMapping("/me")
    public UserDisplayDto updateMyProfile(@CurrentUserId UUID userId,
                                          @Valid @RequestBody UserCreateDto dto) {
        return userService.updateNames(userId, dto.firstName(), dto.lastName());
    }

    @GetMapping("/{id}")
    public UserDisplayDto publicProfile(@PathVariable UUID id) {
        return userService.getById(id);
    }

    @GetMapping("/{id}/recipes")
    public Page<RecipeDisplayDto> recipesByUser(@PathVariable UUID id, Pageable pageable) {
        return recipeService.getByAuthor(id, pageable);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=UserControllerTest"`
Expected: `Tests run: 8, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/UserController.java src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/UserControllerTest.java
git commit -m "feat: user profile endpoints (provision, me, public profile)"
```

---

### Task 5: RecipeController

**Files:**
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/RecipeController.java`
- Test: `src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/RecipeControllerTest.java`

**Interfaces:**
- Consumes: `RecipeService.browse(RecipeFilter, Pageable)`, `.create(UUID, RecipeCreateDto)`, `.getById(UUID)`, `.update(UUID, UUID, RecipeCreateDto)`, `.delete(UUID, UUID)`; `RecipeFilter(String cuisine, Boolean dairyFree, Boolean glutenFree, Boolean vegan, Boolean vegetarian, Integer maxReadyInMinutes, Double minRating)`; `@CurrentUserId` (Task 1).
- Produces: routes `GET|POST /api/recipes`, `GET|PUT|DELETE /api/recipes/{id}`. `POST` answers 201 with a `Location: .../api/recipes/{id}` header.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/RecipeControllerTest.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.exception.ForbiddenOperationException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeFilter;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RecipeController.class)
@Import(SecurityConfig.class)
class RecipeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RecipeService recipeService;
    @MockitoBean
    JwtDecoder jwtDecoder;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static RequestPostProcessor authed() {
        return jwt().jwt(j -> j.subject(USER_ID.toString()));
    }

    /** Minimal valid recipe payload: title + one ingredient. */
    private static final String VALID_RECIPE_JSON = """
            {"title":"Pancakes",
             "ingredients":[{"ingredientName":"Flour","originalText":"2 cups flour"}]}
            """;

    /** RecipeDisplayDto has 28 components; tests only care about id/title. */
    private static RecipeDisplayDto sampleRecipe(UUID id) {
        return new RecipeDisplayDto(id, "user", null, "Pancakes", null, null, null, null, null,
                null, null, null, List.of(), false, false, false, false, List.of(),
                null, null, null, null, null, List.of(), null, 0L, 0L, null);
    }

    @Test
    void browse_anonymous_is200() throws Exception {
        UUID recipeId = UUID.randomUUID();
        when(recipeService.browse(any(), any()))
                .thenReturn(new PageImpl<>(List.of(sampleRecipe(recipeId))));

        mockMvc.perform(get("/api/recipes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(recipeId.toString()));
    }

    @Test
    void browse_bindsFilterParams() throws Exception {
        when(recipeService.browse(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/recipes?cuisine=italian&vegan=true&maxReadyInMinutes=30&minRating=4"))
                .andExpect(status().isOk());

        ArgumentCaptor<RecipeFilter> captor = ArgumentCaptor.forClass(RecipeFilter.class);
        verify(recipeService).browse(captor.capture(), any());
        RecipeFilter filter = captor.getValue();
        assertThat(filter.cuisine()).isEqualTo("italian");
        assertThat(filter.vegan()).isTrue();
        assertThat(filter.maxReadyInMinutes()).isEqualTo(30);
        assertThat(filter.minRating()).isEqualTo(4.0);
        assertThat(filter.dairyFree()).isNull(); // unset params stay null, not false
    }

    @Test
    void browse_pageSizeIsCappedAt50() throws Exception {
        when(recipeService.browse(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/recipes?size=500"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(recipeService).browse(any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void create_is201WithLocationAndJwtIdentity() throws Exception {
        UUID recipeId = UUID.randomUUID();
        when(recipeService.create(eq(USER_ID), any())).thenReturn(sampleRecipe(recipeId));

        mockMvc.perform(post("/api/recipes")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RECIPE_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/recipes/" + recipeId)))
                .andExpect(jsonPath("$.title").value("Pancakes"));
    }

    @Test
    void create_withoutToken_is401() throws Exception {
        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RECIPE_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_missingIngredients_is400() throws Exception {
        mockMvc.perform(post("/api/recipes")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Pancakes\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ingredients").exists());
    }

    @Test
    void getById_anonymous_is200() throws Exception {
        UUID recipeId = UUID.randomUUID();
        when(recipeService.getById(recipeId)).thenReturn(sampleRecipe(recipeId));

        mockMvc.perform(get("/api/recipes/" + recipeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recipeId.toString()));
    }

    @Test
    void getById_malformedUuid_is400() throws Exception {
        mockMvc.perform(get("/api/recipes/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_notOwner_is403ProblemDetail() throws Exception {
        UUID recipeId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ForbiddenOperationException("You don't own this recipe"))
                .when(recipeService).delete(USER_ID, recipeId);

        mockMvc.perform(delete("/api/recipes/" + recipeId).with(authed()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You don't own this recipe"));
    }

    @Test
    void delete_asOwner_is204() throws Exception {
        UUID recipeId = UUID.randomUUID();

        mockMvc.perform(delete("/api/recipes/" + recipeId).with(authed()))
                .andExpect(status().isNoContent());

        verify(recipeService).delete(USER_ID, recipeId);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=RecipeControllerTest"`
Expected: BUILD FAILURE — compilation error, `cannot find symbol: class RecipeController`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/RecipeController.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.controller;

import jakarta.validation.Valid;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeFilter;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final RecipeService recipeService;

    public RecipeController(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    /** Filter params bind straight into the RecipeFilter record; absent = null = no filter. */
    @GetMapping
    public Page<RecipeDisplayDto> browse(@ModelAttribute RecipeFilter filter, Pageable pageable) {
        return recipeService.browse(filter, pageable);
    }

    @PostMapping
    public ResponseEntity<RecipeDisplayDto> create(@CurrentUserId UUID userId,
                                                   @Valid @RequestBody RecipeCreateDto dto) {
        RecipeDisplayDto created = recipeService.create(userId, dto);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    public RecipeDisplayDto getById(@PathVariable UUID id) {
        return recipeService.getById(id);
    }

    @PutMapping("/{id}")
    public RecipeDisplayDto update(@CurrentUserId UUID userId,
                                   @PathVariable UUID id,
                                   @Valid @RequestBody RecipeCreateDto dto) {
        return recipeService.update(userId, id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        recipeService.delete(userId, id);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=RecipeControllerTest"`
Expected: `Tests run: 10, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/RecipeController.java src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/RecipeControllerTest.java
git commit -m "feat: recipe CRUD and filtered browse endpoints"
```

---

### Task 6: RatingController

**Files:**
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/RatingController.java`
- Test: `src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/RatingControllerTest.java`

**Interfaces:**
- Consumes: `RatingService.getForRecipe(UUID, Pageable)` → `Page<RecipeRatingDisplayDto>`; `.rate(UUID userId, UUID recipeId, RecipeRatingCreateDto)`; `.getMyRating(UUID userId, UUID recipeId)` → `Optional<Integer>`; `.deleteMyRating(UUID userId, UUID recipeId)`; `RecipeRatingCreateDto(Integer rating)` (1–5, @NotNull); `@CurrentUserId` (Task 1).
- Produces: routes `GET /api/recipes/{recipeId}/ratings` (public), `PUT|GET|DELETE /api/recipes/{recipeId}/ratings/me` (auth). `GET .../me` responds `{"rating": <int>}` or 404 when the caller hasn't rated.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/RatingControllerTest.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeRatingCreateDto;
import mk.ukim.finki.wp.recipeappbackend.service.RatingService;
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

import java.util.Optional;
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

@WebMvcTest(RatingController.class)
@Import(SecurityConfig.class)
class RatingControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RatingService ratingService;
    @MockitoBean
    JwtDecoder jwtDecoder;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECIPE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static RequestPostProcessor authed() {
        return jwt().jwt(j -> j.subject(USER_ID.toString()));
    }

    @Test
    void listRatings_anonymous_is200() throws Exception {
        when(ratingService.getForRecipe(eq(RECIPE_ID), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/recipes/" + RECIPE_ID + "/ratings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void putMyRating_upsertsWithJwtIdentity() throws Exception {
        mockMvc.perform(put("/api/recipes/" + RECIPE_ID + "/ratings/me")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4}"))
                .andExpect(status().isNoContent());

        verify(ratingService).rate(USER_ID, RECIPE_ID, new RecipeRatingCreateDto(4));
    }

    @Test
    void putMyRating_withoutToken_is401() throws Exception {
        mockMvc.perform(put("/api/recipes/" + RECIPE_ID + "/ratings/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putMyRating_outOfRange_is400() throws Exception {
        mockMvc.perform(put("/api/recipes/" + RECIPE_ID + "/ratings/me")
                        .with(authed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.rating").exists());
    }

    @Test
    void getMyRating_returnsRating() throws Exception {
        when(ratingService.getMyRating(USER_ID, RECIPE_ID)).thenReturn(Optional.of(5));

        mockMvc.perform(get("/api/recipes/" + RECIPE_ID + "/ratings/me").with(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5));
    }

    @Test
    void getMyRating_none_is404() throws Exception {
        when(ratingService.getMyRating(USER_ID, RECIPE_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/recipes/" + RECIPE_ID + "/ratings/me").with(authed()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMyRating_anonymous_is401() throws Exception {
        mockMvc.perform(get("/api/recipes/" + RECIPE_ID + "/ratings/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteMyRating_is204() throws Exception {
        mockMvc.perform(delete("/api/recipes/" + RECIPE_ID + "/ratings/me").with(authed()))
                .andExpect(status().isNoContent());

        verify(ratingService).deleteMyRating(USER_ID, RECIPE_ID);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=RatingControllerTest"`
Expected: BUILD FAILURE — compilation error, `cannot find symbol: class RatingController`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/RatingController.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.controller;

import jakarta.validation.Valid;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeRatingCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeRatingDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.RatingService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/recipes/{recipeId}/ratings")
public class RatingController {

    private final RatingService ratingService;

    public RatingController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    @GetMapping
    public Page<RecipeRatingDisplayDto> list(@PathVariable UUID recipeId, Pageable pageable) {
        return ratingService.getForRecipe(recipeId, pageable);
    }

    /** Rate or re-rate — the service upserts on (recipeId, userId), so PUT is naturally idempotent. */
    @PutMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rate(@CurrentUserId UUID userId,
                     @PathVariable UUID recipeId,
                     @Valid @RequestBody RecipeRatingCreateDto dto) {
        ratingService.rate(userId, recipeId, dto);
    }

    /** 404 = "you haven't rated this one" — the UI shows empty stars. */
    @GetMapping("/me")
    public Map<String, Integer> myRating(@CurrentUserId UUID userId, @PathVariable UUID recipeId) {
        Integer rating = ratingService.getMyRating(userId, recipeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No rating by you for recipe " + recipeId));
        return Map.of("rating", rating);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMyRating(@CurrentUserId UUID userId, @PathVariable UUID recipeId) {
        ratingService.deleteMyRating(userId, recipeId);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=RatingControllerTest"`
Expected: `Tests run: 8, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/RatingController.java src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/RatingControllerTest.java
git commit -m "feat: recipe rating endpoints (list, upsert/get/delete mine)"
```

---

### Task 7: CommentController

**Files:**
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/CommentController.java`
- Test: `src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/CommentControllerTest.java`

**Interfaces:**
- Consumes: `CommentService.getForRecipe(UUID, Pageable)` → `Page<RecipeCommentDisplayDto>`; `.add(UUID userId, UUID recipeId, RecipeCommentCreateDto)`; `.edit(UUID userId, UUID commentId, RecipeCommentCreateDto)`; `.delete(UUID userId, UUID commentId)`; `RecipeCommentCreateDto(String body)`; `RecipeCommentDisplayDto(UUID id, UserDisplayDto user, String body, long likeCount, long dislikeCount, OffsetDateTime createdAt)`; `@CurrentUserId` (Task 1).
- Produces: routes `GET|POST /api/recipes/{recipeId}/comments`, `PUT|DELETE /api/comments/{id}`. POST answers 201 with the created comment body (no Location header — comments have no standalone GET endpoint).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/CommentControllerTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=CommentControllerTest"`
Expected: BUILD FAILURE — compilation error, `cannot find symbol: class CommentController`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/CommentController.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.controller;

import jakarta.validation.Valid;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeCommentCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeCommentDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.CommentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Comments live under their recipe for create/list, but edit/delete address
 * the comment directly — you already know its id from the list response.
 * Moderation policy (author OR recipe owner may delete) is enforced by
 * CommentService, not here.
 */
@RestController
@RequestMapping("/api")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping("/recipes/{recipeId}/comments")
    public Page<RecipeCommentDisplayDto> list(@PathVariable UUID recipeId, Pageable pageable) {
        return commentService.getForRecipe(recipeId, pageable);
    }

    @PostMapping("/recipes/{recipeId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public RecipeCommentDisplayDto add(@CurrentUserId UUID userId,
                                       @PathVariable UUID recipeId,
                                       @Valid @RequestBody RecipeCommentCreateDto dto) {
        return commentService.add(userId, recipeId, dto);
    }

    @PutMapping("/comments/{id}")
    public RecipeCommentDisplayDto edit(@CurrentUserId UUID userId,
                                        @PathVariable UUID id,
                                        @Valid @RequestBody RecipeCommentCreateDto dto) {
        return commentService.edit(userId, id, dto);
    }

    @DeleteMapping("/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        commentService.delete(userId, id);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=CommentControllerTest"`
Expected: `Tests run: 8, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/CommentController.java src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/CommentControllerTest.java
git commit -m "feat: comment endpoints (list, add, edit, delete)"
```

---

### Task 8: ReactionController

**Files:**
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/ReactionController.java`
- Test: `src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/ReactionControllerTest.java`

**Interfaces:**
- Consumes: `ReactionService.getForComment(UUID, Pageable)` → `Page<CommentReactionDisplayDto>`; `.react(UUID userId, UUID commentId, CommentReactionCreateDto)`; `.removeMyReaction(UUID userId, UUID commentId)`; `CommentReactionCreateDto(Boolean isLike)` (@NotNull); `@CurrentUserId` (Task 1).
- Produces: routes `GET /api/comments/{commentId}/reactions` (public), `PUT|DELETE /api/comments/{commentId}/reactions/me` (auth). Completes the API surface.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/ReactionControllerTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=ReactionControllerTest"`
Expected: BUILD FAILURE — compilation error, `cannot find symbol: class ReactionController`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/ReactionController.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.controller;

import jakarta.validation.Valid;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.CommentReactionCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CommentReactionDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.ReactionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/comments/{commentId}/reactions")
public class ReactionController {

    private final ReactionService reactionService;

    public ReactionController(ReactionService reactionService) {
        this.reactionService = reactionService;
    }

    @GetMapping
    public Page<CommentReactionDisplayDto> list(@PathVariable UUID commentId, Pageable pageable) {
        return reactionService.getForComment(commentId, pageable);
    }

    /** Like/dislike — the service upserts and flips the same row, so PUT is idempotent. */
    @PutMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void react(@CurrentUserId UUID userId,
                      @PathVariable UUID commentId,
                      @Valid @RequestBody CommentReactionCreateDto dto) {
        reactionService.react(userId, commentId, dto);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMyReaction(@CurrentUserId UUID userId, @PathVariable UUID commentId) {
        reactionService.removeMyReaction(userId, commentId);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=ReactionControllerTest"`
Expected: `Tests run: 5, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/mk/ukim/finki/wp/recipeappbackend/controller/ReactionController.java src/test/java/mk/ukim/finki/wp/recipeappbackend/controller/ReactionControllerTest.java
git commit -m "feat: comment reaction endpoints (list, upsert/remove mine)"
```

---

### Task 9: Full verification

**Files:** none created — verification only.

**Interfaces:**
- Consumes: everything above.
- Produces: green suite, confirmation the whole API surface works together.

- [ ] **Step 1: Run every new test class together**

Run: `.\mvnw.cmd test "-Dtest=CurrentUserIdArgumentResolverTest,GlobalExceptionHandlerTest,UserControllerTest,RecipeControllerTest,RatingControllerTest,CommentControllerTest,ReactionControllerTest"`
Expected: `Tests run: 46, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 2 (optional, needs DB): context-load test**

Only if `SUPABASE_DB_PASSWORD` is set in the shell (value is in the IntelliJ run configuration):

```powershell
$env:SUPABASE_DB_PASSWORD = "<value from IntelliJ run config>"
.\mvnw.cmd test "-Dtest=RecipeAppBackendApplicationTests"
```

Expected: BUILD SUCCESS. If the env var isn't available, skip — the slice tests are the acceptance gate for this plan, and the app can be smoke-tested from IntelliJ instead.

- [ ] **Step 3: Verify no uncommitted changes remain**

Run: `git status --short`
Expected: no output for `src/` paths (only untracked noise like `target/` or `.claude/`, if anything).
