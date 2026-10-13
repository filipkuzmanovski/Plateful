# Two-Factor Authentication (TOTP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Optional authenticator-app 2FA, enforced on the server. Accounts with a verified TOTP factor must present an `aal2` token for writes and `/me` routes. The frontend adds a code step at sign-in and a settings section to manage up to 2 devices.

**Architecture:**
- **Backend:** a Spring Security filter, placed after JWT validation, checks the token's `aal` claim. It uses a cached `JdbcTemplate` lookup of `auth.mfa_factors` to decide.
- **Frontend:** talks to Supabase Auth MFA directly (`auth.mfa.*`) for enrolment, challenge and verify. The API client and `AuthProvider` learn a new `needs-mfa` state, which drives a `/verify-2fa` page.

**Tech Stack:**
- Spring Boot 4.1 / Spring Security 7 (resource server, JWT) / JdbcTemplate.
- React 19, TS strict, supabase-js v2 MFA API, Vitest + RTL + MSW.

**Spec:** `docs/superpowers/specs/2026-10-13-two-factor-auth-design.md`. It is the binding authority; read it alongside this plan.

## Global Constraints

**Backend response contract:**
- The 401 body is a ProblemDetail with `detail: "Two-factor verification required"` and `code: "mfa_required"`.
- Header: `WWW-Authenticate: Bearer error="insufficient_user_authentication"`.
- If the lookup fails, the response is 503 with `detail: "Could not verify two-factor status"`. Enforcement is fail-closed.

**What is enforced:**
- Only authenticated requests that are (method ≠ GET) OR (path ends with `/me`), whose token `aal` ≠ `"aal2"`, and whose user has a verified factor.
- Public GETs are never blocked.

**Factor lookup:**
- SQL exactly `select exists(select 1 from auth.mfa_factors where user_id = ? and status = 'verified')`. Never select `secret` or any other column.
- Cache: 60 s TTL, at most 10,000 entries (cleared when full).

**Frontend:**
- Max 2 verified TOTP factors per user.
- Exact copy:
  - Sign-in page title: "Two-factor verification"
  - Sign-in page subtitle: "Enter the 6-digit code from your authenticator app."
  - Device choice label: "Which device?"
  - Code field label: "Code"
  - Wrong code: "That code didn't work. Check your app and try again."
  - Removal refused for a half signed-in session: "Please sign in again with your code to make this change."
  - Success: "Two-factor authentication is on"
  - QR image alt: "QR code for your authenticator app"
  - Secret prompt: "Can't scan? Enter this key"
- Code input: `inputMode="numeric"`, `autoComplete="one-time-code"`, `maxLength={6}`, pattern `\d{6}`.
- Golden Hour UI rules: no emojis (`expectNoEmoji()`), secondary text `text-olive/70`, everything clickable is a `<button>`.

**Process:**
- Backend tests: `export JAVA_HOME="$USERPROFILE/.jdks/ms-21.0.9"`, then `./mvnw -q test -Dtest=<Classes>` from `RecipeAppBackend/`. NEVER run unfiltered `mvnw test`. Confirm results with the surefire XML.
- DB tests use EXACTLY the annotations of `RecipeCuisineQueryDbTest`, and are read-only. Export `SUPABASE_DB_PASSWORD` from `RecipeAppBackend/.idea/workspace.xml` without printing it.
- Before compiling the backend, check that the user's running backend has no `spoonacular` launch args (`Get-CimInstance Win32_Process`). If it does, STOP and report (DevTools restarts would re-run the importer).
- Frontend: npm/npx via the Bash tool from `recipe-app-frontend/`. `npx vitest run` and `npm run build` must be green before each commit.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Stage explicit paths only. Push after each task (branch `feature/two-factor-auth`).

## Review Focus

1. **The filter must run exactly once, inside the security chain.** If `MfaEnforcementFilter` were a Spring bean, Boot would also register it as a servlet filter. That copy would run before JWT authentication, see no user, and set OncePerRequestFilter's "already filtered" attribute, so the chain copy would skip and enforcement would be silently disabled. It is therefore NOT a bean. Pinned in Task 2.
2. **A normal password user with no factor (`aal1`, no factor) must never be blocked or slowed** beyond one cached lookup. Pinned in Task 2.
3. **After a correct code the app must move from `needs-mfa` to `ready`,** and later token refreshes at `aal2` must not cause reload loops. The dedupe key is `userId:aal`. Pinned in Task 4.
4. **A half-finished earlier setup** (an unverified factor left behind) must not make the next "Turn on" fail with a duplicate-name error. Leftovers are removed before enrolling. Pinned in Task 6.
5. **A `needs-mfa` user is never trapped.**
   - Public pages still render.
   - Protected pages redirect to `/verify-2fa`.
   - Sign out works from the verify page and the header.
   - A plain 401 still signs out, but `mfa_required` does not.

   Pinned in Task 3, Task 4 and Task 5.

---

### Task 1: Factor lookup with cache (backend)

**Files:**
- Create: `RecipeAppBackend/src/main/java/mk/ukim/finki/wp/recipeappbackend/security/MfaFactorLookup.java`
- Test: `.../security/MfaFactorLookupTest.java` (unit) and `.../repository/MfaFactorLookupDbTest.java` (real DB, read-only; put it in the `repository` package next to the other DB tests)

**Interfaces:**
- Produces: `@Component public class MfaFactorLookup`, with:
  - constructor `MfaFactorLookup(JdbcTemplate jdbc, Clock clock)`;
  - a `@Bean Clock` only if none exists. Prefer `@Autowired(required=false)` plus a fallback to `Clock.systemUTC()`, or a secondary constructor;
  - `public boolean hasVerifiedFactor(UUID userId)`, which throws `DataAccessException` through to the caller;
  - `static final String SQL` and `static final Duration TTL = Duration.ofSeconds(60)`;
  - `static final int MAX_ENTRIES = 10_000`.

- [ ] **Step 1: Write the failing tests.**
  - **Unit tests** (mock `JdbcTemplate`, mutable test `Clock`):
    - `cachesWithinTtl`: two calls 30 s apart query once.
    - `requeriesAfterTtl`: calls at 0 and 61 s query twice.
    - `perUserCache`: two different ids query twice.
    - `sqlNeverTouchesSecret`: `SQL` doesn't contain `"secret"`, and contains `"status = 'verified'"`.
    - `clearsWhenFull`: after `MAX_ENTRIES + 1` distinct ids, the cache size is ≤ `MAX_ENTRIES`. Expose a package-private `int cacheSize()`.
  - **DB test:** `randomUserHasNoFactor`: `hasVerifiedFactor(UUID.randomUUID())` is false.
- [ ] **Step 2: Run them to verify they fail.** Run: `./mvnw -q test -Dtest='MfaFactorLookupTest'`. Expected: a compile failure.
- [ ] **Step 3: Implement.** Use `jdbc.queryForObject(SQL, Boolean.class, userId)`, and `ConcurrentHashMap<UUID, Cached(boolean value, Instant expiresAt)>`.
- [ ] **Step 4: Run them to verify they pass.** Run the unit test, then the DB test with the password exported. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(backend): cached lookup of verified MFA factors`.

### Task 2: Enforcement filter in the security chain (backend)

**Files:**
- Create: `.../security/MfaEnforcementFilter.java`. NOT annotated `@Component`.
- Modify: `.../config/SecurityConfig.java`
  - Inject `MfaFactorLookup` and Jackson's `ObjectMapper` (whichever mapper type the project uses; Task 1 of the seas plan used `tools.jackson.databind.json.JsonMapper`, so check).
  - Add `.addFilterAfter(new MfaEnforcementFilter(lookup, mapper), BearerTokenAuthenticationFilter.class)`.
- Modify: every `@WebMvcTest` that imports `SecurityConfig` (grep `@Import(SecurityConfig.class)` / `@Import({SecurityConfig.class`). Each adds `@MockitoBean MfaFactorLookup mfaFactorLookup;`. Mockito's default `false` keeps today's behaviour.
- Test: create `.../security/MfaEnforcementFilterTest.java`. It is a `@WebMvcTest(UserController.class)` plus `RecipeController`, or whichever controllers give a POST, a `/me` GET and a public GET. It uses `@Import(SecurityConfig.class)`, with `@MockitoBean` for those controllers' services and `MfaFactorLookup`.

**Interfaces:**
- Consumes: `MfaFactorLookup.hasVerifiedFactor(UUID)` from Task 1.
- Produces: `public final class MfaEnforcementFilter extends OncePerRequestFilter` with the constructor `(MfaFactorLookup lookup, <Mapper> mapper)`. It writes the 401 and 503 bodies exactly as in the Global Constraints.

- [ ] **Step 1: Write the failing tests.** Use `jwt().jwt(j -> j.subject(U).claim("aal", "aal1"|"aal2"))`.
  - `postAal1WithoutFactorPasses`: the status is whatever the controller normally returns (2xx or 4xx validation), never 401.
  - `postAal1WithFactorIs401MfaRequired`: 401; `$.code == "mfa_required"`; `$.detail == "Two-factor verification required"`; the `WWW-Authenticate` header contains `insufficient_user_authentication`; and the controller service is never called.
  - `postAal2WithFactorPasses`.
  - `getMeAal1WithFactorIs401`: `GET /api/users/me`.
  - `publicGetAal1WithFactorPasses`: `GET /api/recipes` gives 200.
  - `lookupFailureIs503`: the lookup throws `DataAccessResourceFailureException`, giving 503 and the exact detail.
  - `anonymousUnaffected`: an anonymous POST gives 401 from the resource server, as today. It has no `code` field, and the lookup is never called.
  - `filterIsNotABean` (Review Focus 1): `applicationContext.getBeansOfType(MfaEnforcementFilter.class)` is empty.
- [ ] **Step 2: Run them to verify they fail.** Run: `./mvnw -q test -Dtest=MfaEnforcementFilterTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Path rule: `!"GET".equals(method) || request.getRequestURI().endsWith("/me")`. Read `aal` with `jwt.getClaimAsString("aal")`.
- [ ] **Step 4: Run the full backend regression list plus the new tests.**
  - List: `RecipeServicePhotosTest,RecipeControllerTest,RecipeServiceBrowseTest,RecipeServiceCuisineValidationTest,RecipePhotoPathsTest,GlobalExceptionHandlerTest,CommentControllerTest,RatingControllerTest,ReactionControllerTest,UserControllerTest,CuisineControllerTest,CuisineServiceTest,CuisineCatalogTest,SeaCatalogTest,SpoonacularRecipeTranslatorTest,SpoonacularImportServiceTest,SpoonacularImportRunnerTest,SpoonacularPropertiesTest,SpoonacularClientTest,CurrentUserIdArgumentResolverTest,TextArrayFunctionsTest,MfaFactorLookupTest,MfaEnforcementFilterTest`.
  - Expected: all pass.
- [ ] **Step 5: Commit:** `feat(backend): enforce aal2 for accounts with two-factor on`.

### Task 3: API client understands `mfa_required` (frontend)

**Files:**
- Modify: `recipe-app-frontend/src/api/client.ts`
- Test: `recipe-app-frontend/src/api/client.test.ts`

**Interfaces:**
- Produces:
  - `ApiError` gains `code?: string`. The constructor becomes `(status, detail, errors?, code?)`.
  - `export function setMfaRequiredHandler(fn: (() => void) | null): void`.
  - On a 401 with `problem?.code === 'mfa_required'`, the client does NOT call `signOut`, calls the handler, and throws `ApiError` with `code`. Any other 401 signs out as today.

- [ ] **Step 1: Write the failing tests.**
  - `mfaRequired401DoesNotSignOut`: the MSW handler returns 401 `{detail, code: 'mfa_required'}`. Assert:
    - `supabase.auth.signOut` is not called;
    - the handler spy is called once;
    - the thrown `ApiError` has `code === 'mfa_required'` and `status === 401`.
  - `plain401StillSignsOut`.
- [ ] **Step 2: Run them to verify they fail.** Run: `npx vitest run src/api/client.test.ts`. Expected: FAIL.
- [ ] **Step 3: Implement.** Parse the body once before the 401 branch.
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(frontend): api client recognises mfa_required without signing out`.

### Task 4: `needs-mfa` auth state and routing (frontend)

**Files:**
- Create: `recipe-app-frontend/src/auth/aal.ts`
- Modify: `recipe-app-frontend/src/auth/AuthProvider.tsx`, `recipe-app-frontend/src/auth/ProtectedRoute.tsx` and `recipe-app-frontend/src/components/AppShell.tsx`
- Test: `recipe-app-frontend/src/auth/aal.test.ts`, plus a new `recipe-app-frontend/src/auth/needs-mfa.test.tsx`

**Interfaces:**
- Produces:
  - `aalOf(session: Session | null): 'aal1' | 'aal2' | null`. It decodes the access token's payload (base64url JSON). It returns null for a non-JWT, such as the `'test-token'` used in existing tests, or a missing claim. It never throws.
  - `Status` gains `'needs-mfa'`.
  - In `resolveProfile`:
    - The key is `` `${s.user.id}:${aalOf(s) ?? 'none'}` ``.
    - When `aalOf(s) === 'aal1'`, it awaits `supabase.auth.mfa.getAuthenticatorAssuranceLevel()`. If `data.currentLevel === 'aal1' && data.nextLevel === 'aal2'`, it sets `needs-mfa` and returns without calling `getMe`. A failing AAL call falls back to the normal path.
  - `AuthProvider` registers `setMfaRequiredHandler(() => setStatus('needs-mfa'))` on mount and clears it on unmount.
  - `ProtectedRoute`: on `'needs-mfa'`, when the path is not `/verify-2fa`, it renders `<Navigate to="/verify-2fa" state={{ from: location.pathname }} replace />`.
  - `AppShell`: when the status is `'needs-mfa'`, the header shows a "Sign out" button, plus a "Finish signing in" link to `/verify-2fa`.

- [ ] **Step 1: Write the failing tests.**
  - `aal.test.ts`:
    - A JWT with payload `{aal:'aal2'}` gives `'aal2'`.
    - `'test-token'` gives null.
    - `null` gives null.
    - A malformed base64 token gives null and doesn't throw.
  - `needs-mfa.test.tsx`: mock the supabase client so the session's `access_token` is a JWT with `aal:'aal1'`, and `auth.mfa.getAuthenticatorAssuranceLevel` resolves `{currentLevel:'aal1', nextLevel:'aal2'}`.
    - `protectedRouteRedirectsToVerify`: `renderApp('/me')` lands on `/verify-2fa`, and `/api/users/me` was never requested.
    - `publicPageStillRenders` (Review Focus 5): `renderApp('/')` shows the browse page heading.
    - `upgradeToAal2LoadsProfile` (Review Focus 3): fire `onAuthStateChange` with an `aal2` session. `getMe` is requested once, and the status becomes ready. A second `aal2` refresh event does NOT request `getMe` again.
    - `headerOffersSignOutWhileNeedsMfa`: a "Sign out" button is visible, and clicking it calls `signOut`.
- [ ] **Step 2: Run them to verify they fail.** Run: `npx vitest run src/auth`. Expected: FAIL.
- [ ] **Step 3: Implement** per the Interfaces block. Existing tests use `'test-token'`, so `aalOf` returns null for them and the MFA call is skipped. They must keep passing unchanged.
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(frontend): needs-mfa auth state, redirect and sign-out escape hatch`.

### Task 5: Sign-in code step (frontend)

**Files:**
- Create: `recipe-app-frontend/src/features/auth/VerifyTwoFactorPage.tsx`
- Modify: `recipe-app-frontend/src/AppRoutes.tsx` (add the `/verify-2fa` route, NOT wrapped in `ProtectedRoute`) and `recipe-app-frontend/src/features/auth/LoginPage.tsx`
- Test: `recipe-app-frontend/src/features/auth/VerifyTwoFactorPage.test.tsx` and the LoginPage tests (find the existing file, or create one)

**Interfaces:**
- Consumes: `useAuth().status` and `signOut`, from Task 4.
- Produces:
  - **The page:**
    - It uses `AuthCard` with the exact title and subtitle.
    - It loads `supabase.auth.mfa.listFactors()` and uses `data.totp` (verified TOTP factors).
    - With 2 factors, it shows a radio group labelled "Which device?" using `friendly_name`, with the first selected by default.
    - It has a "Code" input with the exact attributes, a "Verify" submit button, and a secondary "Sign out" button.
  - **Verify:** calls `mfa.challenge({factorId})` then `mfa.verify({factorId, challengeId: challenge.id, code})`.
    - **Success:** navigates to `location.state?.from ?? '/'`.
    - **Error with status 422 or message containing "code":** shows the exact wrong-code copy.
    - **Any other error:** shows Supabase's message.
  - **If the status is already `'ready'`:** the page navigates away immediately.
  - **LoginPage:** after a successful `signInWithPassword`, it awaits `getAuthenticatorAssuranceLevel()`. If the user is half signed-in, it navigates to `/verify-2fa` with `state: { from }`. Otherwise it navigates as today.

- [ ] **Step 1: Write the failing tests.**
  - `correctCodeNavigatesToFrom`: start at `/verify-2fa` with `state.from = '/me'`. Type "123456", then Verify. `verify` is called with `{factorId:'f1', challengeId:'c1', code:'123456'}`, and the page lands on `/me`.
  - `wrongCodeShowsMessage`: `verify` resolves `{error:{status:422, message:'Invalid TOTP code entered'}}`, and the exact copy shows.
  - `twoDevicesChooseFactor`: with factors f1 "Phone" and f2 "Tablet", choosing "Tablet" sends `factorId 'f2'`.
  - `signOutAvailable`: clicking "Sign out" calls `supabase.auth.signOut`.
  - `loginRedirectsWhenHalfSignedIn`: the AAL mock returns aal1/aal2, so login submit goes to `/verify-2fa`. A normal aal1/aal1 result goes to `/`.
  - `expectNoEmoji()`.
- [ ] **Step 2: Run them to verify they fail.** Run: `npx vitest run src/features/auth`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(frontend): two-factor code step at sign-in`.

### Task 6: Two-factor settings on the profile page (frontend)

**Files:**
- Create: `recipe-app-frontend/src/features/profile/TwoFactorSettings.tsx`
- Modify: `recipe-app-frontend/src/features/profile/MyProfilePage.tsx`. Add a `<section>` with `SectionHeading` "Two-factor authentication" below the profile form section, in the same card style.
- Test: `recipe-app-frontend/src/features/profile/TwoFactorSettings.test.tsx`

**Interfaces:**
- Produces: `TwoFactorSettings()`, which takes no props and reads Supabase directly.
  - **Status line:** "Off", or "On: {n} device" / "On: {n} devices".
  - **Device list:** verified factors from `listFactors().data.totp`, showing name and date added, each with a "Remove {name}" button that opens a `ConfirmDialog`.
    - **Confirming:** `mfa.unenroll({factorId})`.
    - **Refused for a half signed-in session** (error status 403, or a message mentioning "aal2" or "AAL2"): shows the exact copy.
    - **Removing the last device:** the dialog text includes "This turns two-factor authentication off."
  - **"Turn on two-factor" / "Add another device"** (hidden when there are 2 verified factors):
    1. A "Device name" input, defaulting to "Authenticator {n+1}".
    2. "Continue":
       - first unenrols every factor in `listFactors().data.all` with `factor_type === 'totp' && status === 'unverified'`;
       - then `mfa.enroll({ factorType: 'totp', friendlyName })`;
       - shows the QR `<img src={data.totp.qr_code} alt="QR code for your authenticator app">`, "Can't scan? Enter this key" with `data.totp.secret` in monospace, a "Code" input (exact attributes), "Confirm" and "Cancel".
    3. Confirm: challenge plus verify against the new factor.
       - Success shows "Two-factor authentication is on" and refreshes the list.
       - A wrong code shows the exact wrong-code copy.
    4. Cancel unenrols the pending factor.

- [ ] **Step 1: Write the failing tests** (mock `@/auth/supabaseClient` `auth.mfa.*`).
  - `enrollShowsQrAndSecretThenConfirms`:
    - Continue gives an `img` with the QR alt, and the secret text.
    - Typing "123456" then Confirm calls `verify` with the new factor id.
    - "Two-factor authentication is on" shows.
  - `removesLeftoverUnverifiedBeforeEnroll` (Review Focus 4): `listFactors.all` contains an unverified TOTP factor "x". `unenroll({factorId:'x'})` is called before `enroll`.
  - `hidesAddAtTwoDevices`: with 2 verified factors there is no "Add another device" button, and the status reads "On: 2 devices".
  - `removeGoesThroughConfirm`: "Remove Phone", then confirm, calls `unenroll({factorId:'f1'})`.
  - `removeRefusedAtAal1ShowsMessage`: `unenroll` resolves `{error:{status:403, message:'AAL2 required'}}`, and the exact copy shows.
  - `cancelUnenrollsPending`.
  - `expectNoEmoji()`.
- [ ] **Step 2: Run them to verify they fail.** Run: `npx vitest run src/features/profile`. Expected: FAIL.
- [ ] **Step 3: Implement** with Golden Hour styling, and the QR shown on a white `bg-paper` tile with padding so phone cameras can read it.
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(frontend): manage two-factor devices on the profile page`.

### Task 7: Verification and dev log

**Files:**
- Modify: `docs/plateful-frontend-dev-log.txt`. Append "PART 6 — TWO-FACTOR AUTHENTICATION": one section per task, the recovery SQL from spec §7, and the rollout check from §8.

- [ ] **Step 1: Run the full verification.** That's the Task 2 regression list, `*DbTest` (where `RecipeCuisineQueryDbTest` may fail only if the imported recipes are still missing; report it), and the frontend `npx vitest run` plus `npm run build`.
- [ ] **Step 2: Write the dev log section, then commit:** `docs: dev log for two-factor authentication`. Push.
