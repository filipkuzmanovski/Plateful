# Backend REST API + Auth Flow — Design

**Date:** 2026-07-13
**Scope:** Expose the existing service layer of `RecipeAppBackend` as a REST API, wire Supabase JWT identity into it, open read endpoints to the public, and add error handling, CORS, and controller tests. A frontend app is a separate, later spec.

## Context

The backend (Spring Boot 4, Java 21, PostgreSQL on Supabase, Flyway) already has a complete domain layer: entities, DTOs, repositories, and services for users, recipes, ratings, comments, and reactions. Security is JWT-only (Supabase Auth JWKS, ES256), currently locking down every route. The only controller is a placeholder `TestController`, which this work deletes.

Two rules already established in the service layer carry through the whole design:

1. **Identity always comes from the verified JWT** (`sub`, `email` claims) — never from a request body.
2. **Ownership/authorization checks live in the services** (e.g. `assertOwnedBy`). Controllers never repeat them; `SecurityConfig` only decides authenticated vs. public.

## Endpoints

All routes under `/api`. **Public** = no token required. **Auth** = valid Supabase JWT required. Pagination uses Spring `Pageable` query params (`page`, `size`), `size` capped at 50, returning standard `Page` JSON.

### Users — `UserController`

| Method | Path | Access | Behavior |
|---|---|---|---|
| POST | `/api/users/me` | Auth | Get-or-create my profile. Body `{firstName, lastName}` (`UserCreateDto`); id + email from JWT. Idempotent (`UserService.getOrCreate`). 200 + `UserDisplayDto`. |
| GET | `/api/users/me` | Auth | My profile. 404 if not yet provisioned. |
| PUT | `/api/users/me` | Auth | Update my names (`UserService.updateNames`), same body/validation as POST. |
| GET | `/api/users/{id}` | Public | Public profile. |
| GET | `/api/users/{id}/recipes` | Public | That user's recipes, paginated (`RecipeService.getByAuthor`). |

### Recipes — `RecipeController`

| Method | Path | Access | Behavior |
|---|---|---|---|
| GET | `/api/recipes` | Public | Browse, paginated. Optional filter params `cuisine`, `dairyFree`, `glutenFree`, `vegan`, `vegetarian`, `maxReadyInMinutes`, `minRating` bound to `RecipeFilter`. |
| POST | `/api/recipes` | Auth | Create from `RecipeCreateDto`. 201 + `Location` header. |
| GET | `/api/recipes/{id}` | Public | Detail: ingredients + rating/comment aggregates. |
| PUT | `/api/recipes/{id}` | Auth | Full edit incl. ingredient replacement; owner-only (service enforces). |
| DELETE | `/api/recipes/{id}` | Auth | Owner-only. 204. |

### Ratings — `RatingController`

| Method | Path | Access | Behavior |
|---|---|---|---|
| GET | `/api/recipes/{recipeId}/ratings` | Public | Paged ratings list. |
| PUT | `/api/recipes/{recipeId}/ratings/me` | Auth | Rate or re-rate (atomic upsert). Body `RecipeRatingCreateDto`. 204. |
| GET | `/api/recipes/{recipeId}/ratings/me` | Auth | My rating; 404 if none (UI pre-fills stars). |
| DELETE | `/api/recipes/{recipeId}/ratings/me` | Auth | Remove my rating. 204. |

### Comments — `CommentController`

| Method | Path | Access | Behavior |
|---|---|---|---|
| GET | `/api/recipes/{recipeId}/comments` | Public | Paged, newest first, with like/dislike counts. |
| POST | `/api/recipes/{recipeId}/comments` | Auth | Add comment (`RecipeCommentCreateDto`). 201. |
| PUT | `/api/comments/{id}` | Auth | Edit; author-only (service enforces). |
| DELETE | `/api/comments/{id}` | Auth | Author or recipe owner (service enforces). 204. |

### Reactions — `ReactionController`

| Method | Path | Access | Behavior |
|---|---|---|---|
| GET | `/api/comments/{commentId}/reactions` | Public | Paged "who reacted" list. |
| PUT | `/api/comments/{commentId}/reactions/me` | Auth | Like/dislike (upsert/flip). Body `CommentReactionCreateDto` (`{isLike}`). 204. |
| DELETE | `/api/comments/{commentId}/reactions/me` | Auth | Un-react. 204. |

## Auth & identity flow

- **Token verification** stays exactly as configured: Spring OAuth2 resource server validates `Authorization: Bearer <jwt>` against the Supabase JWKS endpoint (ES256).
- **Identity extraction:** a custom `@CurrentUserId` annotation + `HandlerMethodArgumentResolver` converts the JWT `sub` claim to `UUID` once, so handlers declare `@CurrentUserId UUID userId` instead of repeating `UUID.fromString(jwt.getSubject())` in every method. `POST /api/users/me` additionally reads the `email` claim from `@AuthenticationPrincipal Jwt`.
- **First-time user flow (frontend contract):**
  1. User signs up / logs in via Supabase on the frontend.
  2. Frontend calls `GET /api/users/me`. 200 → profile exists.
  3. 404 → frontend collects names, calls `POST /api/users/me` (idempotent, safe to retry).
  4. If a user skips provisioning and mutates anyway, services return 404 "User" — frontend treats it as "complete your profile first."
- **Edge case:** a valid JWT whose `sub` is not a UUID → 401, never a 500.

## Security config & CORS

`SecurityConfig` stays stateless JWT-only (CSRF off, `STATELESS` sessions). Route rules, in order:

1. `GET /api/users/me` → authenticated (declared before the public `{id}` matcher).
2. `GET` on `/api/recipes/**`, `/api/users/*`, `/api/users/*/recipes`, `/api/comments/*/reactions` → `permitAll`.
3. `anyRequest()` → authenticated (deny-by-default preserved; covers all mutations and unknown routes).

CORS is enabled in the security chain, driven by property `app.cors.allowed-origins` (default `http://localhost:5173`), allowing standard methods and the `Authorization` header. Production origins are a config change, not a code change.

**Secrets (already done, 2026-07-13):** `spring.datasource.password` reads `${SUPABASE_DB_PASSWORD}`; the value is set in the IntelliJ run configuration (`.idea/workspace.xml`, gitignored). Password rotation was deemed unnecessary because nothing was ever committed with the literal value. Running outside IntelliJ requires setting the variable in that environment.

## Error handling

One `GlobalExceptionHandler` (`@RestControllerAdvice`) produces RFC 9457 ProblemDetail (`application/problem+json`):

| Failure | Status | Notes |
|---|---|---|
| `ResourceNotFoundException` | 404 | e.g. "Recipe with id … not found" |
| `ForbiddenOperationException` | 403 | Service's message passed through |
| `MethodArgumentNotValidException` | 400 | Field→message map in an `errors` extension property |
| Malformed body / bad UUID in path | 400 | `HttpMessageNotReadableException`, type-mismatch |
| Anything else | 500 | Generic detail, no internals leaked; logged server-side |

Missing/expired/invalid JWT → 401 from Spring Security, untouched.

## Components (new code)

| Component | Responsibility |
|---|---|
| `controller/UserController` | User profile endpoints |
| `controller/RecipeController` | Recipe CRUD + browse |
| `controller/RatingController` | Ratings nested under recipes |
| `controller/CommentController` | Comments (nested create/list, flat edit/delete) |
| `controller/ReactionController` | Reactions nested under comments |
| `security/CurrentUserId` (+resolver, +`WebMvcConfigurer` registration) | JWT `sub` → `UUID` parameter injection |
| `exception/GlobalExceptionHandler` | Exception → ProblemDetail mapping |
| `config/SecurityConfig` (modified) | Public-read/auth-write rules + CORS |
| — | `TestController` deleted |

Controllers are thin: extract identity, delegate to the service, map to response status. No business logic.

## Testing

`@WebMvcTest` slice tests per controller — services mocked, real `SecurityConfig` imported, JWTs simulated with `SecurityMockMvcRequestPostProcessors.jwt()`:

- Security rules: public GETs 200 anonymously; mutations 401 without a token; `GET /api/users/me` 401 anonymously.
- Identity wiring: JWT `sub`/`email` arrive as service arguments (argument captors) — guards the "identity never from the body" rule.
- Error mapping: ProblemDetail shape for 404/403/400; validation failures list field errors.
- Binding: filter params → `RecipeFilter`; `Pageable` + size cap.

No full-stack DB integration tests in this phase; the existing context-load test stays. End-to-end verification is manual against Supabase.

## Out of scope

- Frontend application (separate spec, next).
- Admin/moderator roles, recipe import (Spoonacular), image upload.
- API versioning (`/v1`) — single known client.
