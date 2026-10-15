# Saved Recipes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Signed-in users save recipes from cards and recipe pages, and see them in a private "Saved" tab on their own profile.

**Architecture:**
- **Data:** a `saved_recipes` join table (Flyway V4), plus a small service and controller under `/api/users/me/saved`. The saved list reuses `RecipeService`'s batched page assembly, kept in saved order.
- **Security:** the two-factor filter widens to guard any path with a `me` segment.
- **Frontend:** loads the saved IDs once, through a `useSavedIds` hook with optimistic toggles. That hook drives a card bookmark, a recipe-page Save button, and profile tabs.

**Tech Stack:**
- Spring Boot 4.1, Java 21, JPA/Hibernate 7, Flyway, Postgres (Supabase).
- React 19, TS, TanStack Query v5, sonner (toasts), Vitest + RTL + MSW.

**Spec:** `docs/superpowers/specs/2026-10-15-saved-recipes-design.md`. It is the binding authority; read it alongside this plan.

## Global Constraints

**Endpoints** (authenticated, identity from `@CurrentUserId`):

| Method and path | Result |
|---|---|
| `PUT /api/users/me/saved/{recipeId}` | 204, or 404 if the recipe is missing |
| `DELETE /api/users/me/saved/{recipeId}` | 204, always |
| `GET /api/users/me/saved?page=&size=` | `Page<RecipeDisplayDto>`, newest saved first, tie-break `recipe_id` |
| `GET /api/users/me/saved/ids` | `string[]` |

**Two-factor rule:** guard when the method is not GET, OR the decoded path's `/`-split segments contain exactly `me`.

**Exact copy:**
- Toast: "Couldn't update saved recipes. Please try again."
- Card bookmark name: "Save {title}" / "Remove {title} from saved"
- Recipe page button: "Save" / "Saved"
- Tabs: "My recipes" / "Saved"
- Empty state: "No saved recipes yet. Tap the bookmark on any recipe to save it here." plus a button "Browse recipes"

**URL:** the tab param is `?tab=saved`; the default is "My recipes" (`mine`). Switching uses `replace`.

**UI rules:**
- Golden Hour: secondary text `text-olive/70`, no emojis (`expectNoEmoji()`), clickable things are `<button>`s.
- The card bookmark is NEVER nested inside the card's `<Link>`.

**Process:**
- **Safety, BEFORE any backend compile:**
  `powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'RecipeAppBackendApplication' } | ForEach-Object { \$_.CommandLine -match 'spoonacular' }"`
  If that prints True, STOP and report BLOCKED.
- **Backend tests:** `export JAVA_HOME="$USERPROFILE/.jdks/ms-21.0.9"`, then `./mvnw -q test -Dtest=<Classes>` from `RecipeAppBackend/`. NEVER run unfiltered. Confirm via surefire XML.
- **DB tests:** use EXACTLY the annotations of `RecipeCuisineQueryDbTest`, and write only inside the rolled-back `@DataJpaTest` transaction. Export the password without printing it:
  `export SUPABASE_DB_PASSWORD="$(python -c "import re;s=open('.idea/workspace.xml',encoding='utf-8').read();m=re.search(r'name=\"SUPABASE_DB_PASSWORD\" value=\"([^\"]*)\"',s);print(m.group(1) if m else '')")"`
- **Backend regression list:**
  `RecipeServicePhotosTest,RecipeControllerTest,RecipeServiceBrowseTest,RecipeServiceCuisineValidationTest,RecipePhotoPathsTest,GlobalExceptionHandlerTest,CommentControllerTest,RatingControllerTest,ReactionControllerTest,UserControllerTest,CuisineControllerTest,CuisineServiceTest,CuisineCatalogTest,SeaCatalogTest,SearchWordsTest,SpoonacularRecipeTranslatorTest,SpoonacularImportServiceTest,SpoonacularImportRunnerTest,SpoonacularPropertiesTest,SpoonacularClientTest,CurrentUserIdArgumentResolverTest,TextArrayFunctionsTest,MfaFactorLookupTest,MfaEnforcementFilterTest`
  plus the new classes, then `-Dtest='*DbTest'`. Add the new test classes to the list as they appear.
- **Frontend:** npm/npx via the Bash tool from `recipe-app-frontend/`. `npx vitest run` and `npm run build` must be green before each commit.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Stage explicit paths only. Push after each task (branch `feature/saved-recipes`).

## Review Focus

1. **Pressing a card's bookmark** (click, Enter or Space) must save without opening the recipe. Pinned in Task 5.
2. **One user must never see another user's saved list or IDs,** and anonymous calls get 401. Pinned in Task 2.
3. **A half signed-in 2FA session** cannot read `/api/users/me/saved` or `/api/users/me/saved/ids`. Public GETs like `/api/users/{id}/recipes` stay unguarded. Pinned in Task 3.
4. **A saved recipe later deleted by its owner** disappears from the saved list (cascade), and the list doesn't break. Pinned in Task 1 (DB) and Task 2 (service ignores missing ids).
5. **Clicking a bookmark twice quickly** ends in the correct state. The button for that recipe is disabled while its request is in flight. Pinned in Task 4.

---

### Task 1: Table, entity and repository (backend)

**Files:**
- Create: `RecipeAppBackend/src/main/resources/db/migration/V4__saved_recipes.sql`, exactly as in spec §3.1. The tables `users` and `recipes` have been confirmed to exist.
- Create: `.../model/entities/SavedRecipe.java` and `.../model/entities/SavedRecipeId.java` (an `@Embeddable` holding `userId` and `recipeId`).
- Create: `.../repository/SavedRecipeRepository.java`.
- Test: `.../repository/SavedRecipeRepositoryDbTest.java`.

**Interfaces:**
- Produces, in `SavedRecipeRepository extends JpaRepository<SavedRecipe, SavedRecipeId>`:
  - `@Modifying @Query(nativeQuery = true) void insertIgnore(UUID userId, UUID recipeId)`, running `INSERT INTO saved_recipes (user_id, recipe_id) VALUES (:userId, :recipeId) ON CONFLICT (user_id, recipe_id) DO NOTHING`;
  - `@Modifying void deleteByIdUserIdAndIdRecipeId(UUID userId, UUID recipeId)`, or an equivalent bulk JPQL delete;
  - `Page<UUID> findRecipeIdsByUserId(UUID userId, Pageable pageable)`, ordered by `created_at DESC, recipe_id ASC`; write the JPQL or native query with an explicit count query;
  - `List<UUID> findAllRecipeIdsByUserId(UUID userId)`, newest first.
- The entity maps `created_at` as insertable=false, updatable=false (set by the database).

- [ ] **Step 1: Write the failing DB test.** It seeds inside the rolled-back transaction: borrow one existing user read-only, and create 3 recipes.
  - `insertIgnoreIsIdempotent`: `insertIgnore` twice for the same pair gives exactly 1 row.
  - `newestFirstThenRecipeId`: save r1, then r2, then r3 (flush between, and set `created_at` through native updates if needed to force distinct times). `findRecipeIdsByUserId(u, PageRequest.of(0, 2))` gives `[r3, r2]`, with `totalElements` 3.
  - `deletingRecipeCascades`: delete r2 through `recipeRepository`, flush, and `findAllRecipeIdsByUserId` no longer contains r2 (Review Focus 4).
  - `deleteRemovesOnlyThatPair`.
- [ ] **Step 2: Run it to verify it fails.** Run the safety check, then `./mvnw -q test -Dtest=SavedRecipeRepositoryDbTest` with the password exported. Expected: a compile failure.
- [ ] **Step 3: Implement.** V4 reaches the live DB when the user's backend restarts. It's additive, and the spec covers it.
- [ ] **Step 4: Run it to verify it passes.** If it fails with "relation saved_recipes does not exist", wait 30 s for the user's backend to restart and apply V4, then re-run once. If it still fails, report DONE_WITH_CONCERNS; do not apply V4 any other way.
- [ ] **Step 5: Commit:** `feat(backend): saved_recipes table, entity and repository`.

### Task 2: Service and controller (backend)

**Files:**
- Create: `.../service/SavedRecipeService.java` and `.../controller/SavedRecipeController.java`.
- Modify: `.../service/RecipeService.java`. Rename `private Page<RecipeDisplayDto> assemblePage(Page<Recipe>)` to `public Page<RecipeDisplayDto> toDisplayPage(Page<Recipe>)` and update its callers. The behaviour is unchanged.
- Test: `.../service/SavedRecipeServiceTest.java` (Mockito) and `.../controller/SavedRecipeControllerTest.java` (WebMvc slice with `@Import(SecurityConfig.class)` and `@MockitoBean MfaFactorLookup`, as in the other controller tests).

**Interfaces:**
- Consumes: Task 1's repository.
- Produces, in `SavedRecipeService`:
  - `void save(UUID userId, UUID recipeId)`: throws `ResourceNotFoundException` when `!recipeRepository.existsById(recipeId)`;
  - `void unsave(UUID userId, UUID recipeId)`;
  - `Page<RecipeDisplayDto> list(UUID userId, Pageable pageable)`, `@Transactional(readOnly = true)`;
  - `List<UUID> ids(UUID userId)`.
- **`list` algorithm:**
  1. Get the id page (Task 1).
  2. `recipeRepository.findAllById(ids)`.
  3. Order the recipes to match the id order. Skip ids with no recipe (Review Focus 4).
  4. Wrap them in `new PageImpl<>(ordered, idPage.getPageable(), idPage.getTotalElements())`.
  5. Return `recipeService.toDisplayPage(...)`.
- **Controller:** the four endpoints in the Global Constraints. PUT and DELETE return 204. The list takes a `Pageable` with size capped as elsewhere.

- [ ] **Step 1: Write the failing tests.**
  - **Service:**
    - `saveMissingRecipeThrows404`;
    - `saveCallsInsertIgnore`;
    - `unsaveDeletes`;
    - `listKeepsSavedOrderAndSkipsMissing`: the id page is `[r3, r1, rGone]`, and `findAllById` returns `[r1, r3]` in any order. The resulting DTO ids are `[r3, r1]`, with the total from the id page.
    - `idsReturnsOnlyCallersIds`: the repository is called with the caller's id.
  - **Controller:**
    - anonymous `PUT`, `DELETE` and both `GET`s give 401;
    - a PUT gives 204 and calls `save(caller, id)`;
    - a PUT for a missing recipe gives 404;
    - a DELETE gives 204;
    - a GET of the list gives 200 with `$.content[0].id`;
    - a GET of the ids gives a JSON array;
    - `userIdComesFromToken`: a JWT for user A calls the service with A's id, never a path or body value (Review Focus 2).
- [ ] **Step 2: Run them to verify they fail.** Expected: a compile failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the regression list plus the new classes.** Expected: all pass.
- [ ] **Step 5: Commit:** `feat(backend): save, unsave and list saved recipes`.

### Task 3: Two-factor guard on any `me` segment (backend)

**Files:**
- Modify: `.../security/MfaEnforcementFilter.java` (`isGuarded`, around line 103).
- Test: `.../security/MfaEnforcementFilterTest.java`.

**Interfaces:**
- Produces: `isGuarded` returns `!"GET".equals(method)`, OR the decoded path (`UrlPathHelper.defaultInstance.getPathWithinApplication(request)`) split on `/` has any segment equal to `"me"`.

- [ ] **Step 1: Write the failing tests.** All use aal1 with a factor present.
  - `GET /api/users/me/saved` gives 401 `mfa_required`.
  - `GET /api/users/me/saved/ids` gives 401 `mfa_required`.
  - `GET /api/users/m%65/saved`, built with `URI.create`, gives 401.
  - `GET /api/users/{uuid}/recipes` is not guarded: it passes to the controller or gives a non-mfa status (Review Focus 3).
  - The existing tests all stay green.
- [ ] **Step 2: Run it to verify it fails.** Expected: the saved paths return non-401.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run it to verify it passes,** plus the regression list.
- [ ] **Step 5: Commit:** `fix(backend): two-factor guard covers every /me/ path`.

### Task 4: Saved API client and `useSavedIds` hook (frontend)

**Files:**
- Create: `recipe-app-frontend/src/api/saved.ts` and `recipe-app-frontend/src/features/saved/useSavedIds.ts`.
- Test: `recipe-app-frontend/src/features/saved/useSavedIds.test.tsx`.

**Interfaces:**
- Produces, in `api/saved.ts`:
  - `getSavedIds(): Promise<string[]>`
  - `getSavedRecipes(page: number, size?: number): Promise<Page<RecipeDisplay>>`
  - `saveRecipe(id: string): Promise<void>`
  - `unsaveRecipe(id: string): Promise<void>`
- Produces, in `useSavedIds()`:
  - It returns `{ isSaved(id): boolean; isPending(id): boolean; toggle(id): void; ready: boolean }`.
  - The query key is `['savedIds', profile?.id]`, enabled only when `status === 'ready'`.
  - **`toggle` when signed out** (any status other than ready): `navigate('/login', { state: { from: location.pathname + location.search } })`.
  - **`toggle` when signed in:**
    1. Optimistically add or remove the id in the cache.
    2. Call `saveRecipe` or `unsaveRecipe`.
    3. On error, roll back and `toast.error` with the exact copy.
    4. On settle, invalidate `['savedRecipes']`.
  - While a toggle for an id is in flight, `isPending(id)` is true and further toggles for that id are ignored (Review Focus 5).

- [ ] **Step 1: Write the failing tests** (MSW, with a ready signed-in user as in the other tests).
  - `loadsIdsWhenSignedIn`.
  - `toggleSavesOptimistically`: `isSaved` is true before the PUT resolves.
  - `rollsBackAndToastsOnError`: the PUT returns 500, `isSaved` goes back to false, and the toast text is exact.
  - `signedOutTogglesToLogin`: anonymous, so it navigates to `/login` with `state.from` equal to the current path, and no request is made.
  - `ignoresSecondToggleWhilePending`: two quick toggles give exactly one PUT, and the final state is saved.
- [ ] **Step 2: Run it to verify it fails.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.**
- [ ] **Step 5: Commit:** `feat(frontend): saved recipes API and useSavedIds hook`.

### Task 5: Card bookmark and recipe-page Save button (frontend)

**Files:**
- Modify: `recipe-app-frontend/src/components/RecipeCard.tsx`. Wrap it in a `relative` container. The existing `<Link>` stays as the card body, and a sibling `BookmarkButton` is absolutely positioned over the photo's top-right corner.
- Create: `recipe-app-frontend/src/features/saved/BookmarkButton.tsx` and `recipe-app-frontend/src/features/saved/SaveRecipeButton.tsx`.
- Modify: `recipe-app-frontend/src/features/recipe/RecipeDetailPage.tsx`. Add `SaveRecipeButton` near the title actions.
- Test: `recipe-app-frontend/src/features/saved/saveButtons.test.tsx`.

**Interfaces:**
- Consumes: `useSavedIds` from Task 4.
- Produces:
  - **`BookmarkButton({ recipeId, title })`:**
    - a round button, at least 40px, `bg-paper/90`, a soft shadow;
    - lucide `Bookmark`, filled saffron when saved;
    - `aria-label` uses the exact copy; `aria-pressed`;
    - disabled while pending.
  - **`SaveRecipeButton({ recipeId })`:**
    - shows "Save" (outline bookmark) or "Saved" (filled bookmark, with a saffron tint);
    - `aria-pressed`; disabled while pending.

- [ ] **Step 1: Write the failing tests.**
  - `cardBookmarkSavesWithoutNavigating` (Review Focus 1):
    - render the browse page with one card, then `userEvent.click` the "Save {title}" button;
    - the PUT is made and `aria-pressed` becomes true;
    - the location is still `/`;
    - also test with `keyboard('{Enter}')` and `keyboard(' ')` after focusing the button.
  - `bookmarkNotInsideLink`: `button.closest('a')` is null.
  - `detailSaveToggles`: "Save" becomes "Saved" with the PUT, then clicking again goes back to "Save" with the DELETE.
  - `signedOutBookmarkGoesToLogin`.
  - `expectNoEmoji()`.
- [ ] **Step 2: Run it to verify it fails.**
- [ ] **Step 3: Implement.** The existing card hover lift and focus styles must keep working.
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Fix any existing card tests broken only by the new button, with scoped queries.
- [ ] **Step 5: Commit:** `feat(frontend): save recipes from cards and recipe pages`.

### Task 6: Profile tabs with the Saved list (frontend)

**Files:**
- Modify: `recipe-app-frontend/src/features/profile/MyProfilePage.tsx`. The existing "My recipes" section becomes tab panel 1, and the new Saved panel is tab 2.
- Create: `recipe-app-frontend/src/features/saved/SavedRecipesPanel.tsx` and `recipe-app-frontend/src/features/profile/ProfileTabs.tsx`.
- Test: `recipe-app-frontend/src/features/profile/profileTabs.test.tsx`.

**Interfaces:**
- Consumes: `getSavedRecipes` from Task 4, the query key `['savedRecipes', page]`, and the existing card grid and `Pagination`.
- Produces:
  - **`ProfileTabs`:**
    - `role="tablist"`, with the tabs "My recipes" and "Saved";
    - ArrowLeft and ArrowRight move between tabs and select them (automatic activation);
    - the selected tab is read from `?tab=` and written with `setSearchParams(..., { replace: true })`.
  - **`SavedRecipesPanel`:**
    - lists saved recipes with pagination;
    - the empty state uses the exact copy, with a "Browse recipes" link-button to `/`;
    - after a successful unsave, the card disappears (via the `['savedRecipes']` invalidation).
  - Public profiles (`PublicProfilePage`) are untouched.

- [ ] **Step 1: Write the failing tests.**
  - `tabFollowsUrl`: `/me?tab=saved` selects Saved and shows the saved cards.
  - `switchingTabUpdatesUrl`.
  - `arrowKeysMoveTabs`.
  - `emptyStateCopy`.
  - `unsaveRemovesFromSavedTab`.
  - `publicProfileHasNoSavedTab`.
  - `expectNoEmoji()`.
- [ ] **Step 2: Run it to verify it fails.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.**
- [ ] **Step 5: Commit:** `feat(frontend): saved recipes tab on the profile page`.

### Task 7: Verification and dev log

- [ ] **Step 1:** Run the safety check. Then the full backend regression list plus the new classes and `*DbTest`, and the frontend `npx vitest run` plus `npm run build`. Expected: all green, except any already-known data-caused DB skips.
- [ ] **Step 2:** Append "PART 11 — SAVED RECIPES" to `docs/plateful-frontend-dev-log.txt`, one section per task. Commit as `docs: dev log for saved recipes`, and push.
