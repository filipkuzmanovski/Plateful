# Admin Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every new or edited user recipe waits in an admin review queue before it is public, and authors are emailed about the outcome.

**Architecture:**
- **Backend:** Flyway V6 adds review columns. A single `RecipeVisibility` rule gates every read. `RecipeService` applies the status transitions. A new `/api/admin` controller, behind `AdminGuard` plus the widened MFA filter, approves and rejects under a row lock with a revision check. An after-commit event sends the email through SMTP, or to the log when it isn't configured.
- **Frontend:** adds author status UI, Saved stubs and an `under_review` / `unavailable` page, plus the admin queue and review pages.

**Tech Stack:**
- Spring Boot 4 / Java 21 / Hibernate 7 / Flyway / Postgres (Supabase) / spring-boot-starter-mail;
- React 19 / TS / TanStack Query / Radix / Tailwind v4 / Vitest + RTL + MSW.

**Spec:** `docs/superpowers/specs/2026-10-18-admin-review-design.md`

## Global Constraints

### Status values and errors
- **Status values** (DB text): `pending`, `approved`, `rejected`. Java: `RecipeStatus { PENDING, APPROVED, REJECTED }`.
- **Error codes** (ProblemDetail `code` property):

  | Code | Status |
  |---|---|
  | `under_review` | 404, with property `approvedTitle` |
  | `unavailable` | 404, with property `approvedTitle` |
  | `recipe_not_approved` | 409 |
  | `forbidden` | 403 |
  | `admin_mfa_setup_required` | 403 |
  | `mfa_required` | 401 (existing) |
  | `not_pending` | 409 |
  | `recipe_changed` | 409 |

- **The reject reason** is trimmed, 1–500 characters, and required.

### Exact frontend copy
- Create toast (non-admin): "Submitted for review. We'll email you when it's approved."
- Banners:
  - pending: "Waiting for review. Only you can see this recipe."
  - rejected: "This recipe wasn't approved.", then the reason, then the link button "Edit and resubmit"
- Edit notice: "Saving changes sends this recipe back for review. It will be hidden from others until it's approved."
- Badges: "Pending review", "Rejected", "Under review", "Unavailable", "Re-review"
- Tab label: "My recipes · {n} needs attention"
- Under-review page: "This recipe is being reviewed after an edit. Check back soon."
- Unavailable page: "This recipe isn't available right now."
- Header link: "Review queue"
- Queue empty state: "Nothing to review right now."
- Review page:
  - buttons: "Approve", "Reject";
  - the dialog's buttons: "Cancel", "Reject recipe";
  - toasts: "Recipe approved", "Recipe rejected".
- Conflict and error messages:
  - `recipe_changed`: "This recipe changed. Reload to review the latest version." with the button "Reload"
  - `not_pending`: "This recipe was already reviewed."
  - 404 on approve or reject: "This recipe is no longer in the queue."
  - Admin without two-factor: "Set up two-factor authentication to use the review queue."

### Emails
Copy them verbatim from spec §4.6. The subject has CR and LF stripped from the title. The sign-off is "— The Plateful team".

### UI rules
- Golden Hour tokens: saffron for pending, danger for rejected.
- No emojis. Assert with `expectNoEmoji()`.
- Every clickable thing is a `<button>` or a link.

### Safety, BEFORE any backend compile

    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'RecipeAppBackendApplication' } | ForEach-Object { \$_.CommandLine -match 'spoonacular' }"

If that prints True, STOP and report BLOCKED. The importer is gone, but this check also catches a stale launch configuration.

### Backend tests
- Set `export JAVA_HOME="$USERPROFILE/.jdks/ms-21.0.9"`, then run `./mvnw -q test -Dtest=<Classes>` from `RecipeAppBackend/`. NEVER run unfiltered.
- Clear `target/surefire-reports` before counting, and confirm counts from the surefire XML.

### DB tests
- Use EXACTLY the annotations of `RecipeCuisineQueryDbTest`.
- Write only inside the rolled-back `@DataJpaTest` transaction.
- For throwaway users, reuse the `newUser` approach from `SavedRecipeRepositoryDbTest.savesAreIsolatedPerUser`.
- Export the password without printing it (from `RecipeAppBackend/`):

      export SUPABASE_DB_PASSWORD="$(python -c "import re;s=open('.idea/workspace.xml',encoding='utf-8').read();m=re.search(r'name=\"SUPABASE_DB_PASSWORD\" value=\"([^\"]*)\"',s);print(m.group(1) if m else '')")"

### Backend regression list
Add each task's new classes to it:

    RecipeServicePhotosTest,RecipeControllerTest,RecipeServiceBrowseTest,RecipeServiceCuisineValidationTest,RecipePhotoPathsTest,GlobalExceptionHandlerTest,CommentControllerTest,RatingControllerTest,ReactionControllerTest,UserControllerTest,CuisineControllerTest,CuisineServiceTest,CuisineCatalogTest,SeaCatalogTest,SearchWordsTest,CurrentUserIdArgumentResolverTest,TextArrayFunctionsTest,MfaFactorLookupTest,MfaEnforcementFilterTest,SavedRecipeServiceTest,SavedRecipeControllerTest,RecipeFilterTest

Then run `-Dtest='*DbTest'`.

### Frontend
- Run npm and npx through the Bash tool, from `recipe-app-frontend/`.
- `npx vitest run` and `npm run build` must be green before each commit.

### Commits
- End each message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Stage explicit paths only.
- Push after each task to `feature/admin-review`.
- Never print secrets.

## Review Focus

1. **The author edits a recipe that is already pending.** It stays pending, and `submitted_at` is unchanged, so it keeps its place in the queue while `revision` still increases. Task 3: `editWhilePendingKeepsQueuePosition`.
2. **The author's account was deleted** (`user_id` is null through ON DELETE SET NULL) while the recipe is pending. Only admins can see it, approving still works, and no email is attempted. Task 2: `authorlessPendingVisibleOnlyToAdmin`. Task 6: `approveAuthorlessRecipeSendsNoEmail`.
3. **Two admins act on the same recipe,** or one admin double-clicks. Exactly one decision wins, and the second gets 409 `not_pending`. Task 6: `secondDecisionGetsNotPending`.
4. **A signed-out visitor opens an old link** to a recipe that is under re-review. They see the under-review page with the approved title, not "Recipe not found". Task 9: `signedOutVisitorSeesUnderReviewPage`.
5. **A whitespace-only reject reason** gets 400. A reason containing line breaks keeps them in the body, and the subject is unaffected. Task 6: `blankReasonIs400`. Task 7: `reasonLineBreaksStayInBodyOnly`.

---

### Task 1: Schema, entities and profile DTO

**Files:**
- Create:
  - `RecipeAppBackend/src/main/resources/db/migration/V6__admin_review.sql`
  - `.../model/RecipeStatus.java`
  - `.../model/displayDTO/MyProfileDto.java`
  - `.../model/displayDTO/RecipeReviewDto.java`
  - Test: `.../repository/AdminReviewSchemaDbTest.java`
- Modify:
  - `.../model/entities/User.java`
  - `.../model/entities/Recipe.java`
  - `.../model/displayDTO/RecipeDisplayDto.java`
  - `.../repository/RecipeRepository.java`
  - `.../service/UserService.java`
  - `.../controller/UserController.java`
  - Test: `.../controller/UserControllerTest.java`

(`...` = `RecipeAppBackend/src/main/java/mk/ukim/finki/wp/recipeappbackend` or the matching `src/test/java` path.)

**Interfaces:**
- **Produces:**
  - **`RecipeStatus`:** the enum, mapped with an `AttributeConverter` to the lowercase text.
  - **`Recipe` fields:**
    - `status`
    - `rejectionReason`
    - `approvedTitle`
    - `everApproved`
    - `submittedAt` (`OffsetDateTime`)
    - `reviewedAt`
    - `reviewedBy` (`User`, LAZY)
    - `revision` (`int`)

    Each has a getter and a setter.
  - **`User.isAdmin()`:** a getter only. The column is `insertable=false, updatable=false`, so nothing in the app can set it.
  - **`record RecipeReviewDto(String status, String rejectionReason, boolean everApproved, int revision, OffsetDateTime submittedAt)`**, built with `static RecipeReviewDto of(Recipe r)`. It uses the lowercase status.
  - **`RecipeDisplayDto`:** gains a final component `RecipeReviewDto review`. The existing `fromEntity(...)` overload passes `null`, and a new `withReview(RecipeReviewDto)` copy method sets it.
  - **`record MyProfileDto(UUID id, String firstName, String lastName, boolean isAdmin, long rejectedCount)`.**
  - **`RecipeRepository.countByUserIdAndStatus(UUID userId, RecipeStatus status)`:** returns a `long`.
  - **`UserService`:**
    - `MyProfileDto myProfile(UUID id)`
    - `getOrCreate(...)` and `updateNames(...)` now return `MyProfileDto`
    - `getById(...)` (public) still returns `UserDisplayDto`
  - **`UserController`:** `POST`, `GET` and `PUT /api/users/me` return `MyProfileDto`.

Steps:

- [ ] **Step 1: Write the V6 migration verbatim from spec §3.1.** First confirm the latest migration is V5 (`ls src/main/resources/db/migration`).
- [ ] **Step 2: Write the failing DB test `AdminReviewSchemaDbTest`.**
  - **`existingRecipesAreApproved`:** `count(*) where status <> 'approved'` is 0. `count(*) where approved_title is distinct from title` is 0. `count(*) where not ever_approved` is 0.
  - **`newRecipeDefaultsToPending`:** a new `Recipe` saved through the repository in the transaction reads back as `PENDING`, with `revision == 0` and `everApproved == false`. In Java the field is initialised: `private RecipeStatus status = RecipeStatus.PENDING;`.
  - **`isAdminDefaultsToFalse`:** a throwaway user reads back with `isAdmin() == false`.
  - **`countByUserIdAndStatus`:** a throwaway user with one rejected recipe and one approved recipe gets 1 for `REJECTED`.
- [ ] **Step 3: Run it to verify it fails.**

  Run: `./mvnw -q test -Dtest=AdminReviewSchemaDbTest` (after the safety check and the password export).

  Expected: compilation FAIL (`RecipeStatus` and the new fields don't exist).
- [ ] **Step 4: Implement the entity fields, the converter, the DTOs, the repository count and the `MyProfileDto` wiring.**
- [ ] **Step 5: Add the controller tests to `UserControllerTest`.**
  - **`myProfileIncludesIsAdminAndRejectedCount`:** `jsonPath("$.isAdmin")` is false and `$.rejectedCount` is 0, using a mocked `UserService` returning a `MyProfileDto`.
  - **`updateMyProfileIgnoresIsAdmin`:** a PUT body containing `"isAdmin": true` reaches `updateNames(id, first, last)`, with no other mutation. Verify on the mock, and that `UserCreateDto` has no such field (the test just posts it, and the service call has only the names).
- [ ] **Step 6: Run the regression list, `UserControllerTest` and `*DbTest`.** Expected: all green, plus 4 DB tests and 2 controller tests.
- [ ] **Step 7: Commit:** `feat(backend): review columns, admin flag and profile dto`.

### Task 2: One visibility rule on every read

**Files:**
- Create:
  - `.../security/OptionalCurrentUserId.java`
  - `.../service/RecipeVisibility.java`
  - `.../exception/RecipeHiddenException.java`
  - Test: `.../repository/RecipeVisibilityDbTest.java`
  - Test: `.../service/RecipeVisibilityTest.java`
- Modify:
  - `.../security/CurrentUserIdArgumentResolver.java` (or a sibling resolver registered in `WebConfig`)
  - `.../specification/RecipeSpecifications.java`
  - `.../repository/RecipeRepository.java` (`countCuisines`)
  - `.../service/RecipeService.java` (`getById`, `browse`, `getByAuthor`)
  - `.../controller/RecipeController.java`
  - `.../controller/UserController.java` (`recipesByUser`)
  - `.../exception/GlobalExceptionHandler.java`
  - Tests: `RecipeControllerTest`, `CurrentUserIdArgumentResolverTest`, `GlobalExceptionHandlerTest`

**Interfaces:**
- **Consumes:** Task 1's fields and `RecipeReviewDto.of`.
- **Produces:**
  - **`@OptionalCurrentUserId UUID viewerId`:** null when the request has no authenticated JWT.
  - **`RecipeVisibility` (`@Component`):**
    - `boolean isAdmin(UUID viewerId)`: false for null; otherwise reads `users.is_admin`.
    - `boolean canSee(Recipe r, UUID viewerId)`
    - `void requireVisible(Recipe r, UUID viewerId)`: when not visible, throws `RecipeHiddenException` if `everApproved`, otherwise `ResourceNotFoundException`.
    - `boolean showsReview(Recipe r, UUID viewerId)`: author or admin.
  - **`RecipeHiddenException(UUID id, String approvedTitle, RecipeStatus status)`:** the handler maps it to 404 with `code` `under_review` (for PENDING) or `unavailable` (for REJECTED), plus the property `approvedTitle`.
  - **`RecipeSpecifications.approvedOnly()`:** a `Specification<Recipe>`.
  - **`RecipeRepository.findByUserIdAndStatus(UUID, RecipeStatus, Pageable)`:** returns a `Page<Recipe>`.
  - **`UserRepository.isAdmin(UUID id)`:** a `@Query` returning a `boolean`. It is false when there is no row.
  - **`RecipeService`:**
    - `getById(UUID recipeId, UUID viewerId)`
    - `browse(...)`, unchanged signature, ANDs `approvedOnly()`
    - `getByAuthor(UUID authorId, UUID viewerId, Pageable)`: all statuses with `review` for the author or an admin; otherwise approved only with `review = null`
  - **`countCuisines`:** the native query gains `where status = 'approved'`.

Steps:

- [ ] **Step 1: Write the failing DB test `RecipeVisibilityDbTest`.**
  - Throwaway author A, other user O and admin M (set `is_admin` with a native update inside the transaction).
  - Recipes: approved P, pending N (never approved), and pending E (`everApproved` true).
  - Tests:
    - **`browseShowsApprovedOnlyForEveryone`:** the browse specification returns P and never N or E.
    - **`cuisineCountsIgnoreUnapproved`:** give N a unique cuisine key; `countCuisines()` has no entry for that key.
    - **`profileQueriesSplitByStatus`:** `findByUserId(A)` returns 3, and `findByUserIdAndStatus(A, APPROVED)` returns only P.
    - **`isAdminReadsFlag`:** the repository query behind `RecipeVisibility.isAdmin` (e.g. `UserRepository.isAdmin(UUID)`, a `@Query` returning a boolean) is true for M and false for O.
- [ ] **Step 2: Write the failing unit and controller tests.**
  - `RecipeVisibilityTest` (mocked `UserRepository`):
    - a truth table for `canSee` and `requireVisible`, covering the exception type and status per case;
    - **`authorlessPendingVisibleOnlyToAdmin`:** a pending recipe with a null user; `canSee(r, admin)` is true and `canSee(r, other)` is false.
  - `RecipeServiceBrowseTest` gains **`getByAuthorBranchesOnViewer`:** with the viewer as author or admin, `findByUserId` is called and every DTO has `review` set. With another viewer or null, `findByUserIdAndStatus(..., APPROVED)` is called and `review` is null.
  - `RecipeControllerTest`:
    - **`getUnderReviewIs404WithCode`:** `jsonPath("$.code")` is `"under_review"` and `$.approvedTitle` is `"Old Title"`.
    - **`getRejectedEverApprovedIs404Unavailable`.**
    - **`getNeverApprovedIsPlain404`:** there is no `code` property.
    - **`authorGetsReviewBlock`:** with a JWT, `$.review.status` is `"pending"`.
  - `CurrentUserIdArgumentResolverTest`: **`optionalIsNullWithoutToken`** and **`optionalIsSubWithToken`**.
- [ ] **Step 3: Run them to verify they fail.** Expected: compile failures, or wrong statuses.
- [ ] **Step 4: Implement it.** `requireVisible` is the only place that decides between the hidden 404 and the plain 404.
- [ ] **Step 5: Run the regression list, the new classes and `*DbTest`.** Expected: green. Existing tests calling `getById(id)` or `getByAuthor(id, pageable)` are updated to pass a viewer (null where they were anonymous). List each one in the report.
- [ ] **Step 6: Commit:** `feat(backend): one visibility rule for recipe reads`.

### Task 3: Status transitions on create and edit

**Files:**
- Modify: `.../service/RecipeService.java` (`create`, `update`)
- Test: `.../service/RecipeServiceReviewStatusTest.java` (new)

**Interfaces:**
- **Consumes:** `RecipeVisibility.isAdmin(UUID)` from Task 2.
- **Produces:**
  - `create` and `update` set the status fields per spec §4.1.
  - A package-private helper `void markApproved(Recipe r, User reviewer)`, reused by Task 6. It sets: APPROVED, `everApproved = true`, `approvedTitle = title`, `rejectionReason = null`, `reviewedBy = reviewer`, `reviewedAt = now`.

Steps:

- [ ] **Step 1: Write the failing tests** (mocked repositories, the same style as `RecipeServicePhotosTest`).
  - **`userCreateIsPending`:** status PENDING, `submittedAt` not null, `everApproved` false.
  - **`adminCreateIsApproved`:** APPROVED, `approvedTitle` equals the title, `reviewedBy` is the admin.
  - **`editApprovedGoesPending`:** status PENDING, `submittedAt` advanced, `revision` goes from 3 to 4, `everApproved` stays true, `approvedTitle` is unchanged.
  - **`editRejectedGoesPendingAndClearsReason`.**
  - **`editWhilePendingKeepsQueuePosition`:** `submittedAt` is unchanged and `revision` increases.
  - **`photoOnlyEditBumpsRevision`:** only the photos list differs, and `revision` still increases.
  - **`adminEditStaysApproved`:** `revision` increases and the status is APPROVED, with `approvedTitle` equal to the new title.
- [ ] **Step 2: Run them to verify they fail.**
- [ ] **Step 3: Implement it in `create` and `update`.** `update` always increments `revision` after validation passes.
- [ ] **Step 4: Run the regression list plus `RecipeServiceReviewStatusTest`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(backend): recipes enter review on create and edit`.

### Task 4: Comments, ratings and reactions respect review status

**Files:**
- Modify:
  - `.../service/CommentService.java`
  - `.../service/RatingService.java`
  - `.../service/ReactionService.java`
  - the matching controllers (add `@OptionalCurrentUserId` to the public GETs)
  - `.../exception/GlobalExceptionHandler.java`
- Create: `.../exception/RecipeNotApprovedException.java` (mapped to 409 with `code: "recipe_not_approved"`)
- Test: `CommentControllerTest`, `RatingControllerTest`, `ReactionControllerTest`, plus a new `.../service/InteractionReviewGuardTest.java`

**Interfaces:**
- **Consumes:** `RecipeVisibility.requireVisible` and `canSee` from Task 2.
- **Produces:** a shared helper `RecipeVisibility.requireApprovedForInteraction(Recipe r, UUID viewerId)`:
  - not visible: the same exceptions as `requireVisible`;
  - visible but not APPROVED: `RecipeNotApprovedException`.

  For reactions, the recipe is reached through the comment.

Steps:

- [ ] **Step 1: Write the failing tests in `InteractionReviewGuardTest`.**
  - For each of `add`, `edit`, `delete` (comments), `rate`, `deleteMyRating`, `react` and `removeMyReaction`:
    - on a pending recipe, called by a non-author: a 404 type;
    - called by the author: `RecipeNotApprovedException`.
  - For each read (`getForRecipe` on comments and ratings, `getForComment`, `getMyReactions`, `getMyRating`): the not-visible case throws the 404 type, and the approved case passes.
- [ ] **Step 2: Write the failing controller test:** **`commentOnPendingAsAuthorIs409`**, with `jsonPath("$.code")` equal to `"recipe_not_approved"`.
- [ ] **Step 3: Run them to verify they fail.**
- [ ] **Step 4: Implement it.**
- [ ] **Step 5: Run the regression list plus the new class.** Expected: green.
- [ ] **Step 6: Commit:** `feat(backend): interactions only on approved recipes`.

### Task 5: Saved list with stubs

**Files:**
- Create:
  - `.../model/displayDTO/SavedRecipeStubDto.java`
  - `.../model/displayDTO/SavedItemDto.java`
- Modify:
  - `.../service/SavedRecipeService.java`
  - `.../controller/SavedRecipeController.java`
- Test: `SavedRecipeServiceTest`, `SavedRecipeControllerTest`

**Interfaces:**
- **Consumes:** `RecipeVisibility` from Task 2.
- **Produces:**
  - **`record SavedRecipeStubDto(UUID id, String approvedTitle, String state)`:** the state is `"under_review"` or `"unavailable"`.
  - **`record SavedItemDto(String kind, RecipeDisplayDto recipe, SavedRecipeStubDto stub)`:** the kind is `"recipe"` or `"stub"`, and exactly one of the other two fields is non-null.
  - **`SavedRecipeService.list(UUID userId, Pageable)`:** returns a `Page<SavedItemDto>` in saved order. Never-approved recipes that aren't visible are skipped.
  - **`save(userId, recipeId)`:** 404 when not visible to the user.
  - **`GET /api/users/me/saved`:** returns `Page<SavedItemDto>`.

Steps:

- [ ] **Step 1: Write the failing tests.**
  - `SavedRecipeServiceTest`:
    - **`listMixesCardsAndStubsInSavedOrder`:** saved order E (under review), P, R (rejected and ever approved) gives kinds stub, recipe, stub; states `under_review` and `unavailable`; the stub's `approvedTitle` is the approved title, not the current title.
    - **`saveHiddenRecipeIs404`.**
    - **`authorSeesOwnPendingAsCard`.**
  - `SavedRecipeControllerTest`: **`listJsonShape`**, checking `$.content[0].kind` and `$.content[0].stub.state`.
- [ ] **Step 2: Run them to verify they fail.**
- [ ] **Step 3: Implement it.** Assemble the visible recipes through the existing batched `toDisplayPage` path (keep it batched), then interleave the stubs in saved order.
- [ ] **Step 4: Run the regression list and `*DbTest`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(backend): saved list keeps re-reviewed recipes as stubs`.

### Task 6: Admin review API

**Files:**
- Create:
  - `.../security/AdminGuard.java`
  - `.../service/AdminReviewService.java`
  - `.../controller/AdminReviewController.java`
  - `.../model/createDTO/ReviewDecisionDto.java`
  - `.../service/RecipeReviewedEvent.java`
  - `.../exception/ReviewConflictException.java`
  - `.../exception/AdminForbiddenException.java`
- Modify:
  - `.../security/MfaEnforcementFilter.java` (`isGuarded`: the segments contain `me` OR `admin`)
  - `.../exception/GlobalExceptionHandler.java`
  - `.../repository/RecipeRepository.java`
- Test:
  - `.../service/AdminReviewServiceTest.java`
  - `.../controller/AdminReviewControllerTest.java`
  - `MfaEnforcementFilterTest`
  - `.../repository/AdminQueueDbTest.java`

**Interfaces:**
- **Consumes:** `RecipeService.markApproved` (Task 3), `RecipeVisibility.isAdmin` (Task 2), `MfaFactorLookup` (existing).
- **Produces:**
  - **`AdminGuard.requireAdmin(UUID userId)`:**
    - not an admin: `AdminForbiddenException`, mapped to 403 with `code: "forbidden"`;
    - an admin with no verified factor: `AdminForbiddenException`, mapped to 403 with `code: "admin_mfa_setup_required"`.
  - **`record ReviewDecisionDto(@NotNull Integer revision, @Size(max = 500) String reason)`:** bean validation covers the null revision and the length.
  - **`InvalidReviewReasonException`:** thrown by `reject` when the trimmed reason is blank, or longer than 500. It maps to 400 with `errors: { reason: "..." }`, in the same style as `InvalidSortException`. Add it to the Create list.
  - **`RecipeRepository`:**
    - `@Lock(PESSIMISTIC_WRITE) Optional<Recipe> findForReviewById(UUID id)`
    - `Page<Recipe> findByStatusOrderBySubmittedAtAscIdAsc(RecipeStatus status, Pageable)`
    - `long countByStatus(RecipeStatus)`
  - **`AdminReviewService`:**
    - `Page<RecipeDisplayDto> pending(Pageable)`
    - `long pendingCount()`
    - `RecipeDisplayDto get(UUID id)` (with `review`)
    - `void approve(UUID adminId, UUID id, int revision)`
    - `void reject(UUID adminId, UUID id, int revision, String reason)`
  - **`ReviewConflictException(String code)`:** `not_pending` or `recipe_changed`, mapped to 409.
  - **`record RecipeReviewedEvent(UUID recipeId, boolean approved, String title, String reason, String authorEmail, UUID authorId, UUID adminId)`:** published with `ApplicationEventPublisher` inside the transaction.
  - **Endpoints:** as listed in spec §4.4. Every one calls `AdminGuard` first.

Steps:

- [ ] **Step 1: Write the failing service tests (`AdminReviewServiceTest`).**
  - **`approveSetsApprovedAndPublishesEvent`:** APPROVED, `approvedTitle` equals the title, `reviewedBy` is the admin, and one event with `approved = true`.
  - **`rejectStoresTrimmedReasonAndPublishes`:** a reason of `"  Too short  "` is stored as `"Too short"`.
  - **`blankReasonIs400`:** `"   "` throws `InvalidReviewReasonException`, and the controller test `blankReasonIs400` asserts the 400 status and `$.errors.reason`.
  - **`staleRevisionIsRecipeChanged`.**
  - **`notPendingIsNotPending`.**
  - **`secondDecisionGetsNotPending`:** approve, then reject with the same revision, gives `not_pending`.
  - **`approveAuthorlessRecipeSendsNoEmail`:** the event's `authorEmail` is null.
  - **`selfReviewEventMarksAdmin`:** when the author is the admin, `authorId` equals `adminId`.
- [ ] **Step 2: Write the failing controller tests (`AdminReviewControllerTest`).**
  - **`nonAdminIs403Forbidden`:** checks every route.
  - **`adminWithoutFactorIs403Setup`.**
  - **`approve204`, `reject204`.**
  - **`reasonOver500Is400`.**
  - **`blankReasonIs400`.**
  - **`pendingCountShape`:** `$.count`.
- [ ] **Step 3: Add the filter tests to `MfaEnforcementFilterTest`.**
  - **`adminGetAal1WithFactorIs401`:** `GET /api/admin/recipes/pending`.
  - **`adminGetAal2Passes`.**
  - **`encodedAdminSegmentIs401`:** `/api/%61dmin/recipes/pending`.
  - **`lookalikeSegmentNotGuarded`:** `GET /api/recipes/administrator-picks` is not treated as admin. It is a 404 route, so assert the filter lets it through.
- [ ] **Step 4: Write the failing DB test `AdminQueueDbTest`.**
  - **`queueOldestFirstWithIdTieBreak`:** three pending recipes, two with an equal `submitted_at`, come back in order by `submitted_at`, then by id.
  - **`lockedFetchReturnsRow`.**
- [ ] **Step 5: Run them to verify they fail.**
- [ ] **Step 6: Implement it.** Approve and reject are `@Transactional`: `findForReviewById`, then check `status == PENDING` (otherwise `not_pending`), then check the revision (otherwise `recipe_changed`), then mutate, then publish.
- [ ] **Step 7: Run the regression list, the new classes and `*DbTest`.** Expected: green.
- [ ] **Step 8: Commit:** `feat(backend): admin review queue with approve and reject`.

### Task 7: Review emails

**Files:**
- Modify:
  - `RecipeAppBackend/pom.xml` (add `spring-boot-starter-mail`)
  - `src/main/resources/application.properties`
  - `.../RecipeAppBackendApplication.java` (`@EnableAsync`)
- Create, in the new package `.../mail/`:
  - `ReviewEmailSender.java` (the interface)
  - `SmtpReviewEmailSender.java`
  - `LoggingReviewEmailSender.java`
  - `ReviewEmails.java` (pure text building)
  - `ReviewEmailListener.java`
  - `MailConfig.java`
- Test: `.../mail/ReviewEmailsTest.java`, `.../mail/ReviewEmailListenerTest.java`, `.../mail/MailConfigTest.java`

**Interfaces:**
- **Consumes:** `RecipeReviewedEvent` (Task 6).
- **Produces:**
  - **`interface ReviewEmailSender { void send(String to, String subject, String body); }`**
  - **`ReviewEmails`:**
    - `static Email approved(String title, String link)`
    - `static Email rejected(String title, String reason, String link)`
    - `record Email(String subject, String body)`
  - **Properties:**
    - `spring.mail.host=smtp.gmail.com`
    - `spring.mail.port=587`
    - `spring.mail.username=${MAIL_USERNAME:}`
    - `spring.mail.password=${MAIL_PASSWORD:}`
    - `spring.mail.properties.mail.smtp.auth=true`
    - `spring.mail.properties.mail.smtp.starttls.enable=true`
    - `plateful.frontend-url=${FRONTEND_URL:http://localhost:5173}`
  - **`MailConfig`:** picks `SmtpReviewEmailSender` when `spring.mail.username` is non-blank, otherwise `LoggingReviewEmailSender`. The from address is the username, with the display name "Plateful".
  - **`ReviewEmailListener`:** `@TransactionalEventListener(phase = AFTER_COMMIT) @Async`.
    - It skips when `authorEmail` is null or `authorId.equals(adminId)`.
    - The link is `{frontendUrl}/recipes/{id}` when approved, and `{frontendUrl}/recipes/{id}/edit` when rejected.
    - It catches every exception and logs it at WARN.

Steps:

- [ ] **Step 1: Write the failing tests.**
  - `ReviewEmailsTest`:
    - **`approvedTextIsExact`:** the full expected subject and body, from spec §4.6, with title "Lemon Garlic Shrimp".
    - **`rejectedTextIsExact`.**
    - **`subjectStripsLineBreaks`:** the title `"A\r\nBcc: x@y"` gives a subject containing neither `\r` nor `\n`.
    - **`reasonLineBreaksStayInBodyOnly`.**
  - `ReviewEmailListenerTest`, with a mocked sender:
    - **`sendsApprovedEmail`.**
    - **`skipsAuthorless`.**
    - **`skipsSelfReview`.**
    - **`senderFailureIsSwallowed`.**
  - `MailConfigTest`, using `ApplicationContextRunner`:
    - **`blankUsernameUsesLoggingSender`.**
    - **`usernameUsesSmtpSender`.**
- [ ] **Step 2: Run them to verify they fail.**
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run the regression list plus the new classes.** Expected: green.
- [ ] **Step 5: Commit:** `feat(backend): email authors about review decisions`.

### Task 8: Frontend data layer and author surfaces

**Files:**
- Modify:
  - `recipe-app-frontend/src/lib/types.ts`
  - `src/api/client.ts` (ApiError gains `approvedTitle?: string`, parsed from the problem body)
  - `src/api/users.ts`
  - `src/auth/AuthProvider.tsx` (the profile type becomes `MyProfile`)
  - `src/features/authoring/RecipeFormPage.tsx`
  - `src/features/recipe/RecipeDetailPage.tsx`
  - `src/components/RecipeCard.tsx`
  - `src/features/profile/ProfileTabs.tsx`
  - `src/features/profile/MyProfilePage.tsx`
- Create: `src/features/review/ReviewStatusBanner.tsx`, `src/features/review/StatusBadge.tsx`
- Test: `src/features/review/authorReview.test.tsx`, plus the fixtures in `src/test/fixtures.ts`

**Interfaces:**
- **Consumes:** the backend JSON from Tasks 1–5.
- **Produces:**
  - **Types:**
    - `type ReviewStatus = 'pending' | 'approved' | 'rejected'`
    - `interface RecipeReview { status: ReviewStatus; rejectionReason: string | null; everApproved: boolean; revision: number; submittedAt: string }`
    - `RecipeDisplay.review?: RecipeReview | null`
    - `interface MyProfile extends UserDisplay { isAdmin: boolean; rejectedCount: number }`
  - **`useAuth().profile`:** a `MyProfile | null`.
  - **`<StatusBadge status>`:** renders nothing for approved.
  - **`<ReviewStatusBanner review recipeId>`.**
  - **`RecipeCard`:** shows a `StatusBadge` when `recipe.review` is present and not approved.

Steps:

- [ ] **Step 1: Write the failing tests (`authorReview.test.tsx`).**
  - **`createShowsSubmittedToast`:** non-admin; asserts the exact toast copy.
  - **`adminCreateHasNoReviewToast`.**
  - **`pendingBannerForAuthor`:** the exact copy, and comments, rating and reactions are absent.
  - **`rejectedBannerShowsReasonAndResubmitLink`:** the link points to `/recipes/{id}/edit`.
  - **`editApprovedShowsNotice`:** non-admin, status approved; the exact notice copy.
  - **`adminEditHasNoNotice`.**
  - **`myRecipesBadges`:** "Pending review" and "Rejected" appear on the right cards.
  - **`needsAttentionLabel`:** `rejectedCount: 2` gives "My recipes · 2 needs attention".
  - `expectNoEmoji()` in each.
- [ ] **Step 2: Run them to verify they fail.**

  Run: `npx vitest run src/features/review`

  Expected: FAIL.
- [ ] **Step 3: Implement it.** Hide `CommentsSection`, `RatingBox` and the reactions when `recipe.review && recipe.review.status !== 'approved'`.
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: all green. Existing tests that mock `/api/users/me` get `isAdmin: false, rejectedCount: 0` added to the fixtures. List them.
- [ ] **Step 5: Commit:** `feat(frontend): review status for authors`.

### Task 9: Saved stubs and the hidden-recipe pages

**Files:**
- Modify:
  - `src/api/saved.ts` (`getSavedRecipes` returns `Page<SavedItem>`)
  - `src/features/saved/SavedRecipesPanel.tsx`
  - `src/features/recipe/RecipeDetailPage.tsx`
  - `src/lib/types.ts`
- Create:
  - `src/features/saved/SavedStubCard.tsx`
  - `src/features/recipe/HiddenRecipePage.tsx`
- Test: `src/features/saved/savedStubs.test.tsx`

**Interfaces:**
- **Consumes:** `SavedItemDto` and the 404 codes from Tasks 2 and 5. `ApiError.approvedTitle` comes from Task 8.
- **Produces:**
  - `type SavedItem = { kind: 'recipe'; recipe: RecipeDisplay; stub: null } | { kind: 'stub'; recipe: null; stub: { id: string; approvedTitle: string; state: 'under_review' | 'unavailable' } }`
  - `<HiddenRecipePage recipeId approvedTitle state>`

Steps:

- [ ] **Step 1: Write the failing tests.**
  - **`stubCardShowsApprovedTitleAndBadge`:** both states, with a placeholder and no `<img>`.
  - **`stubBookmarkUnsaves`:** a DELETE is sent and the card disappears after the refetch.
  - **`underReviewPageCopy`:** a 404 with `code: "under_review"` renders the exact copy, the approved title as a heading, and the Save/Saved button.
  - **`unavailablePageCopy`.**
  - **`signedOutVisitorSeesUnderReviewPage`:** no session, same assertions.
  - **`plainNotFoundUnchanged`:** a 404 with no code still shows the existing "Recipe not found".
  - `expectNoEmoji()`.
- [ ] **Step 2: Run them to verify they fail.**
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green. Existing saved tests are updated to the `SavedItem` shape; list them.
- [ ] **Step 5: Commit:** `feat(frontend): saved stubs and under-review page`.

### Task 10: Admin queue and review pages

**Files:**
- Create:
  - `src/api/admin.ts`
  - `src/features/admin/ReviewQueuePage.tsx`
  - `src/features/admin/ReviewRecipePage.tsx`
  - `src/features/admin/RejectDialog.tsx`
  - `src/features/admin/AdminMfaPanel.tsx`
  - `src/features/admin/AdminRoute.tsx`
- Modify: `src/AppRoutes.tsx`, `src/components/AppShell.tsx` (the header link)
- Test: `src/features/admin/admin.test.tsx`

**Interfaces:**
- **Consumes:** the endpoints from Task 6, and `useAuth().profile.isAdmin` from Task 8.
- **Produces:**
  - **`admin.ts`:**
    - `getPendingRecipes(page)`
    - `getPendingCount()`
    - `getReviewRecipe(id)`
    - `approveRecipe(id, revision)`
    - `rejectRecipe(id, revision, reason)`
  - **Query keys:** `['admin','pending',page]`, `['admin','pendingCount']`, `['admin','recipe',id]`.
  - **Invalidation:** a decision invalidates `['admin']`, `['recipes']`, `['recipe', id]`, `['userRecipes']` and `['savedRecipes']`.
  - **`<AdminRoute>`:** non-admins get the existing "Page not found" empty state.
  - **Routes:** `/admin/review` and `/admin/review/:id`.
  - **Rendering:** the review page renders the recipe body with the same components `RecipeDetailPage` uses (extract a shared `RecipeBody` if needed). It has no comments, rating or reactions.

Steps:

- [ ] **Step 1: Write the failing tests (`admin.test.tsx`).**
  - **`headerLinkOnlyForAdmins`:** the link is shown with the count badge "3", and is absent for non-admins.
  - **`nonAdminRouteIsNotFound`.**
  - **`queueListsOldestFirstWithReReviewTag`.**
  - **`queueEmptyState`.**
  - **`approveMovesToNext`:** POST `{revision}`, the "Recipe approved" toast, and the location becomes the next pending id.
  - **`approveLastGoesToQueue`.**
  - **`rejectRequiresReasonWithCounter`:** "Reject recipe" is disabled until there is a non-blank reason, and the counter shows `n/500`.
  - **`rejectSendsReason`.**
  - **`recipeChangedShowsReload`:** the exact copy, and Reload refetches.
  - **`notPendingMessage`.**
  - **`approve404Message`:** "This recipe is no longer in the queue."
  - **`adminWithoutMfaSeesPanel`:** a 403 with `code: "admin_mfa_setup_required"` shows the exact copy and a link to `/me`.
  - `expectNoEmoji()`.
- [ ] **Step 2: Run them to verify they fail.**
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(frontend): admin review queue`.

### Task 11: Verification and dev log

- [ ] **Step 1: Run everything.**
  1. The safety check.
  2. The full backend regression list plus every new class from Tasks 1–7, then `*DbTest`.
  3. `npx vitest run` and `npm run build`.

  Expected: all green. Report the counts from the surefire XML.
- [ ] **Step 2: Append "PART 13 — ADMIN REVIEW" to `docs/plateful-frontend-dev-log.txt`.**
  - One section per task, in the style of PART 11–12.
  - Include the rollout steps from spec §7: the admin SQL, the 2FA requirement and the optional Gmail setup.
  - Commit as `docs: dev log for admin review`, and push.
