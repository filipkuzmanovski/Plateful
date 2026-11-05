# Likes and Liked Cookbooks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Signed-in users publicly like published recipes, then choose 5 to 10 liked recipes and turn them into a private, printable keepsake book with their name on the cover.

**Architecture:** Three new tables (`recipe_likes`, `cookbooks`, `cookbook_recipes`) behind two services and two `/api/users/me/...` controllers that follow the Saved recipes code. The frontend adds a like button with an optimistic count, a Cookbooks profile tab, a pick page and a book reader whose leaves are all in the DOM, so printing needs no extra fetch. No AI provider is called.

**Tech Stack:** Spring Boot 4, Java 21, Flyway, Postgres; React 19, TypeScript, Vite, Tailwind v4, TanStack Query, Vitest, MSW.

**Spec:** `docs/superpowers/specs/2026-11-05-likes-and-cookbooks-design.md`

## Global Constraints

- Limits: 5 to 10 distinct recipes per cookbook; subtitle at most 80 characters; at most 20 cookbooks per user; the Create button needs 5 likes.
- Display default for an empty subtitle: "Recipes I loved". Cover title: "The cookbook of {first} {last}".
- Exact conflict messages: "You can have up to 20 cookbooks." and "Some of these recipes are no longer available."
- No emojis in the UI; every new screen's test calls `expectNoEmoji()` from `@/test/noEmoji`.
- Use role-named colour tokens from `src/index.css`. Tailwind v4 resolves two conflicting unprefixed utilities on one element by stylesheet order, not class order.
- Backend shell: `export JAVA_HOME="$USERPROFILE/.jdks/ms-21.0.9"` (bash) or `$env:JAVA_HOME = "$env:USERPROFILE\.jdks\ms-21.0.9"` (PowerShell). Never run unfiltered `mvnw test`.
- DbTests never use `@Import`. They skip themselves when V12 is not live yet, using `Assumptions` on `SELECT to_regclass('public.recipe_likes')`.
- Commits stage explicit paths only, never `tools/dataset/README.md`, and every message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- A pending control uses `aria-disabled`, not `disabled`, so it keeps keyboard focus. Tap targets are at least 40 px.

**Regression commands**

- Backend, in `RecipeAppBackend/`: `./mvnw test "-Dtest=!RecipeAppBackendApplicationTests,!*DbTest"`. Expected: BUILD SUCCESS.
- Frontend, in `recipe-app-frontend/`: `npx vitest run`, then `npm run build`, then `npm run lint`. Expected: all pass.

## Review Focus

1. **Fast double click on a heart:** the second click is ignored and the count moves by exactly 1. Test in Task 4.
2. **Another account in the same tab:** one user's likes and cookbooks are never served to the next, because every private query key carries the user id. Test in Task 5.
3. **Odd `?page=` values (`abc`, `0`, `-1`, `99`, `1.5`):** the reader shows the cover and does not crash. Test in Task 7.
4. **Arrow keys while the delete dialog is open or focus is in a field:** the page does not turn. Test in Task 7.
5. **A book whose chapters are all stubs:** the opening leaves out cuisines and total time, the contents still lists the titles, and nothing crashes. Test in Task 7.

---

### Task 1: Like storage and the public count

**Files:**
- Create: `RecipeAppBackend/src/main/resources/db/migration/V12__likes_and_cookbooks.sql` (exactly the SQL in spec section 3)
- Create: `.../model/entities/RecipeLike.java`, `.../model/entities/RecipeLikeId.java` (mirror `SavedRecipe`, `SavedRecipeId`)
- Create: `.../model/projection/RecipeLikeCount.java`, `.../repository/RecipeLikeRepository.java`
- Modify: `.../model/displayDTO/RecipeDisplayDto.java`, `.../service/RecipeService.java`
- Modify: every caller of `RecipeDisplayDto.fromEntity` or its constructor, including `RecipeControllerTest`, and any test that constructs `RecipeService`
- Test: `.../repository/RecipeLikeRepositoryDbTest.java`, plus additions to `RecipeServiceBrowseTest`

(`...` is `RecipeAppBackend/src/main/java/mk/ukim/finki/wp/recipeappbackend`, or the matching `src/test/java` path for tests.)

**Interfaces:**
- Produces:

```java
public record RecipeLikeCount(UUID recipeId, Long likeCount) {}

public interface RecipeLikeRepository extends JpaRepository<RecipeLike, RecipeLikeId> {
    int insertIgnore(UUID userId, UUID recipeId);          // rows inserted, 0 or 1
    void deleteByIdUserIdAndIdRecipeId(UUID userId, UUID recipeId);
    Page<UUID> findRecipeIdsByUserId(UUID userId, Pageable pageable);   // newest like first, recipe_id tie-break
    List<UUID> findAllRecipeIdsByUserId(UUID userId);      // same order
    long countByIdRecipeId(UUID recipeId);
    List<RecipeLikeCount> findLikeCounts(Collection<UUID> recipeIds);
    List<UUID> findLikedAmong(UUID userId, Collection<UUID> recipeIds);
}
```

- `RecipeDisplayDto` gains `long likeCount`, placed directly after `commentCount`. `fromEntity` gains a `long likeCount` parameter in the same position, and `withReview` carries it.

- [ ] **Step 1: Write the failing DbTest `RecipeLikeRepositoryDbTest`**

Same setup as `SavedRecipeRepositoryDbTest`, with the V12 skip added. Tests:
- `insertIgnore_twice_keepsOneRow`: the first call returns 1, the second returns 0, one row exists.
- `insertIgnore_missingRecipe_insertsNothing`: returns 0 with a random recipe id and throws nothing.
- `deleteRecipe_cascadesToLikes`.
- `findLikeCounts_omitsRecipesWithNoLikes`.
- `findRecipeIdsByUserId_isNewestFirst`.
- `findLikedAmong_returnsOnlyTheUsersLikes`.

- [ ] **Step 2: Add the failing unit test to `RecipeServiceBrowseTest`**

`toDisplayPage_fillsLikeCountsInOneQuery`: mock `findLikeCounts` to return `(r1, 3)` and nothing for `r2`. Assert `likeCount` is 3 and 0, and verify `findLikeCounts` is called once.

- [ ] **Step 3: Run it**

Run: `./mvnw test "-Dtest=RecipeServiceBrowseTest"`
Expected: compile failure, because `RecipeLikeRepository` does not exist.

- [ ] **Step 4: Implement**

- `insertIgnore` is a native `@Modifying @Transactional` statement, which is what makes a missing recipe insert nothing:

```sql
INSERT INTO recipe_likes (user_id, recipe_id)
SELECT CAST(:userId AS uuid), r.id FROM recipes r WHERE r.id = :recipeId
ON CONFLICT (user_id, recipe_id) DO NOTHING
```

- In `RecipeService`, the batched path calls `findLikeCounts` next to `findCommentCounts`, with absent meaning 0. The single-recipe path calls `countByIdRecipeId`.

- [ ] **Step 5: Run the tests**

Run the backend regression command.
Expected: BUILD SUCCESS. The DbTest skips without the DB password.

- [ ] **Step 6: Commit**

`git add` the files above. Message: `feat(backend): store recipe likes and expose a public like count`

---

### Task 2: Like API

**Files:**
- Create: `.../service/LikeService.java`, `.../controller/LikeController.java`
- Modify: `.../service/SavedRecipeService.java`
- Test: `.../service/LikeServiceTest.java`, `.../controller/LikeControllerTest.java`

**Interfaces:**
- Consumes: `RecipeLikeRepository`; `RecipeVisibility.requireApprovedForInteraction(Recipe, UUID)`, which throws `RecipeNotApprovedException` (409) for a visible, unapproved recipe.
- Produces:
  - `SavedRecipeService.assembleItems(List<UUID> orderedIds, UUID viewerId): List<SavedItemDto>`, public, extracted unchanged from `list`, and also used by Task 3.
  - `LikeService.like(userId, recipeId)`, `unlike(userId, recipeId)`, `list(userId, pageable): Page<SavedItemDto>`, `ids(userId): List<UUID>`.
  - Routes under `/api/users/me/likes`, as in spec 4.2.

- [ ] **Step 1: Write the failing tests**

`LikeServiceTest`, in the mock style of `SavedRecipeServiceTest`:
- `like_approvedRecipe_inserts`.
- `like_missingRecipe_throws404`.
- `like_ownPendingRecipe_throwsNotApproved`.
- `like_hiddenRecipe_throwsHidden`.
- `like_recipeDeletedDuringInsert_throws404`: `findById` finds it, `insertIgnore` returns 0, `existsById` is false.
- `like_alreadyLiked_isNoOp`: `insertIgnore` returns 0, `existsById` is true, no exception.
- `unlike_alwaysDeletes`, and it never loads the recipe.
- `list_keepsLikeOrderWithStubs`.

`LikeControllerTest`, in the style of `SavedRecipeControllerTest`:
- PUT and DELETE return 204 for the JWT's user.
- Without a JWT, the routes return 401.
- GET returns the page shape; `/ids` returns the list.
- A malformed UUID returns 400.

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw test "-Dtest=LikeServiceTest,LikeControllerTest"`
Expected: compile failure.

- [ ] **Step 3: Implement**

`like` does `findById`, then the 404, then `requireApprovedForInteraction`, then `insertIgnore`. If it inserted 0 rows and `existsById` is false, it throws `ResourceNotFoundException("Recipe", id)`. `list` is `@Transactional(readOnly = true)`, as in Saved. Move the assembly out of `SavedRecipeService.list` into `assembleItems` without changing behaviour.

- [ ] **Step 4: Run the tests**

Run: `./mvnw test "-Dtest=LikeServiceTest,LikeControllerTest,SavedRecipeServiceTest,SavedRecipeControllerTest"`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

Message: `feat(backend): like and unlike recipes`

---

### Task 3: Cookbook API

**Files:**
- Create: `.../model/entities/Cookbook.java`, `CookbookRecipe.java`, `CookbookRecipeId.java`
- Create: `.../model/projection/CookbookRecipeCount.java`
- Create: `.../repository/CookbookRepository.java`, `CookbookRecipeRepository.java`
- Create: `.../model/createDTO/CookbookCreateDto.java`
- Create: `.../model/displayDTO/CookbookSummaryDto.java`, `CookbookDto.java` (fields exactly as in spec 4.2)
- Create: `.../exception/InvalidCookbookException.java`, `CookbookConflictException.java`
- Create: `.../service/CookbookService.java`, `.../controller/CookbookController.java`
- Modify: `.../exception/GlobalExceptionHandler.java` (400 and 409 `ProblemDetail` carrying the exception's message), `.../repository/UserRepository.java`
- Test: `.../service/CookbookServiceTest.java`, `.../controller/CookbookControllerTest.java`, `.../repository/CookbookRepositoryDbTest.java`

**Interfaces:**
- Consumes: `RecipeLikeRepository.findLikedAmong`, `SavedRecipeService.assembleItems`.
- Produces:

```java
public record CookbookCreateDto(List<UUID> recipeIds, String subtitle) {}

// CookbookService
List<CookbookSummaryDto> list(UUID userId);             // newest volume first, recipeCount from one batched query
CookbookSummaryDto create(UUID userId, CookbookCreateDto body);
CookbookDto get(UUID userId, UUID cookbookId);          // 404 when not the caller's
void delete(UUID userId, UUID cookbookId);              // 404 when not the caller's

// UserRepository
@Lock(LockModeType.PESSIMISTIC_WRITE) Optional<User> findForUpdateById(UUID id);
```

- Routes under `/api/users/me/cookbooks`, as in spec 4.2. POST returns 201.

- [ ] **Step 1: Write the failing `CookbookServiceTest`**

Each assertion follows spec 4.1:
- `create_blankSubtitle_storesNull` and `create_trimsSubtitle`.
- `create_subtitle81Chars_rejected` and `create_subtitleWithControlChar_rejected`.
- `create_null4or11Ids_rejected`.
- `create_duplicateIds_rejected`: 5 ids with one repeated.
- `create_validates_beforeLockingTheUser`: `findForUpdateById` is never called when the body is invalid.
- `create_at20Cookbooks_conflict`, with the exact cap message.
- `create_recipeNotLiked_conflict` and `create_recipeNotApproved_conflict`, with the exact "no longer available" message; nothing is saved.
- `create_volumeIsMaxPlusOne`: max 3 gives 4, none gives 1.
- `create_positionsFollowRequestOrder`, starting at 1.
- `get_otherUsersCookbook_404` and `delete_otherUsersCookbook_404`.
- `get_returnsChaptersInPositionOrder_andLiveOwnerName`.
- `list_fillsRecipeCounts`.

- [ ] **Step 2: Write the failing `CookbookControllerTest`**

- POST returns 201 with a body.
- POST maps `InvalidCookbookException` to 400 and `CookbookConflictException` to 409, with `$.detail` set to the message.
- GET and DELETE return 200 and 204, and 404 when the service throws.
- Every route returns 401 without a JWT.
- `id` and `userId` values come from the JWT, not the path.

- [ ] **Step 3: Write the failing `CookbookRepositoryDbTest`**

- `unique_userAndVolume`.
- `deleteRecipe_removesItFromCookbook`: the cookbook row remains.
- `deleteCookbook_cascadesToItsRecipes`.
- `findRecipeIdsInOrder_followsPosition`, including after a gap.
- `kind_defaultsToLiked`.

- [ ] **Step 4: Run to verify they fail**

Run: `./mvnw test "-Dtest=CookbookServiceTest,CookbookControllerTest"`
Expected: compile failure.

- [ ] **Step 5: Implement**

- `create` is `@Transactional`, in this order: validate the body (400), `findForUpdateById`, check the cap, check likes and approval, compute the volume, insert. This is the order in spec 4.1.
- Control characters are rejected with `Character.isISOControl`.
- `get` is `@Transactional(readOnly = true)`.
- `delete` calls `deleteById` and relies on the DB cascade, because `CookbookRecipe` is not mapped as a child collection.
- Do not call any AI.

- [ ] **Step 6: Run the backend regression command**

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

Message: `feat(backend): create, read and delete cookbooks`

---

### Task 4: Like button and counts in the frontend

**Files:**
- Create: `recipe-app-frontend/src/lib/useOptimisticIds.ts`
- Create: `src/api/likes.ts`
- Create: `src/features/likes/useLikedIds.ts`, `useLikeControl.ts`, `likeCounts.ts`, `LikeButton.tsx`
- Modify: `src/features/saved/useSavedIds.ts`, so it becomes a thin wrapper over `useOptimisticIds`
- Modify: `src/components/RecipeCard.tsx`, `src/features/recipe/RecipeDetailPage.tsx`, `src/features/content/PrivacyPage.tsx`
- Modify: `src/lib/types.ts` (`RecipeDisplay.likeCount: number`), `src/test/fixtures.ts` (`likeCount: 0`)
- Test: `src/features/likes/likeButton.test.tsx`, `src/features/likes/likeCounts.test.ts`

**Interfaces:**
- Produces:

```ts
// api/likes.ts
getLikedIds(): Promise<string[]>
getLikedRecipes(page: number, size?: number): Promise<Page<SavedItem>>
likeRecipe(id: string): Promise<void>
unlikeRecipe(id: string): Promise<void>

// lib/useOptimisticIds.ts: the body of today's useSavedIds, parameterised
useOptimisticIds(options: {
  keyName: string;                       // key is [keyName, userId]
  fetchIds: () => Promise<string[]>;
  add: (id: string) => Promise<void>;
  remove: (id: string) => Promise<void>;
  errorMessage: string;
  onOptimistic?: (id: string, nowOn: boolean) => void;   // also called with the inverse on failure
  onSettled?: () => void;
}): { isOn(id): boolean; isPending(id): boolean; toggle(id): void; ready: boolean }

// features/likes/likeCounts.ts
withLikeDelta<T>(data: T, recipeId: string, delta: number): T   // pure; unknown shapes come back unchanged
patchLikeCount(queryClient: QueryClient, recipeId: string, delta: number): void

// features/likes/useLikedIds.ts
useLikedIds(): { isLiked(id): boolean; isPending(id): boolean; toggle(id): void; ready: boolean; ids: string[] }

// features/likes/useLikeControl.ts: same contract as useSaveControl, with `liked` for `saved`

// features/likes/LikeButton.tsx
<LikeButton recipe={recipe} variant="card" | "page" />
```

- [ ] **Step 1: Write the failing `likeCounts.test.ts`**

`withLikeDelta` handles:
- a single recipe;
- `Page<RecipeDisplay>`;
- `Page<SavedItem>`, patching only `kind: 'recipe'`;
- `{ chapters: SavedItem[] }`.

It leaves a different recipe's object identity alone, returns unknown shapes and `undefined` unchanged, and never goes below 0.

- [ ] **Step 2: Write the failing `likeButton.test.tsx`**

Use the setup in `saveButtons.test.tsx` (`renderAt`, `signedIn`, MSW):
- A card shows the count. Its accessible name matches `/^Like Bruschetta Pork & Pasta, 28 likes$/` for a recipe with `likeCount: 28`, and it uses "1 like" for one.
- Clicking sends PUT, sets `aria-pressed`, and shows 29 at once.
- Clicking again sends DELETE and shows 28.
- A failed PUT reverts the heart and the count, and shows a toast.
- **Review Focus 1:** two rapid clicks send exactly one request, and the count is 29 until it settles.
- Signed out, a click goes to `/login`.
- Not rendered when `review.status` is `pending`. Rendered when `review` is null.
- The recipe page shows a heart beside Save.
- `expectNoEmoji()`.

- [ ] **Step 3: Run to verify they fail**

Run: `npx vitest run src/features/likes`
Expected: FAIL, because the modules are missing.

- [ ] **Step 4: Implement**

- `useOptimisticIds`: move the logic from `useSavedIds` without changing behaviour. `useSavedIds` keeps its public names (`isSaved`, `isPending`, `toggle`, `ready`), its error text, and its invalidation of `['savedRecipes']` through `onSettled`.
- `useLikedIds` uses key name `likedIds`. `onOptimistic` calls `patchLikeCount` with +1 or -1, and also on rollback.
- It must not invalidate `['likedRecipes']` on toggle, so an unliked card stays in place (spec section 6). An unliked card reloads when the tab next mounts.
- `patchLikeCount` uses `queryClient.setQueriesData` on `['recipes']`, `['userRecipes']`, `['savedRecipes']`, `['likedRecipes']`, `['recipe', recipeId]` and `['cookbook']`.
- `LikeButton`, card variant: a sibling of the card link, `absolute top-3 left-3 z-10`, a pill at least 40 px tall on `bg-surface`, with a lucide `Heart` that is `fill-accent` when liked, plus the count in `tabular-nums`. Page variant: the outline `Button` style of `SaveRecipeButton`, with the count.
- In `RecipeCard`, move the Video badge from top left to `bottom-3 left-3` inside the photo, since the heart now owns top left. Update its comment.
- Add the heart before Save in `RecipeDetailPage` actions.
- `PrivacyPage`, `public-content` section: add a paragraph saying that the number of likes on a recipe is public, and that who liked a recipe may be shown publicly in a later version of the site.

- [ ] **Step 5: Run the frontend regression commands**

Existing tests that query buttons by `/like/i` on a recipe page may now match two buttons. Narrow those queries to the comment reaction's own name, and do not rename the new button.
Expected: all pass.

- [ ] **Step 6: Commit**

Message: `feat(frontend): like recipes with a public count`

---

### Task 5: Cookbooks tab

**Files:**
- Create: `src/api/cookbooks.ts`, `src/features/cookbooks/constants.ts`, `src/features/cookbooks/CookbooksPanel.tsx`, `src/features/cookbooks/CoverThumb.tsx`, `src/features/cookbooks/LikedRecipesList.tsx`
- Modify: `src/features/profile/ProfileTabs.tsx`, `src/features/profile/MyProfilePage.tsx`, `src/lib/types.ts`, `src/index.css`
- Modify: `src/features/profile/profileTabs.test.tsx`, for the new tab and its order
- Test: `src/features/cookbooks/cookbooksPanel.test.tsx`

**Interfaces:**
- Consumes: `getLikedRecipes`, `useLikedIds` from Task 4.
- Produces:

```ts
// lib/types.ts
interface CookbookSummary { id: string; volume: number; subtitle: string | null; createdAt: string; recipeCount: number }
interface Cookbook { id: string; volume: number; subtitle: string | null; createdAt: string;
  ownerFirstName: string; ownerLastName: string; chapters: SavedItem[] }

// api/cookbooks.ts
getCookbooks(): Promise<CookbookSummary[]>
getCookbook(id: string): Promise<Cookbook>
createCookbook(body: { recipeIds: string[]; subtitle?: string }): Promise<CookbookSummary>
deleteCookbook(id: string): Promise<void>

// features/cookbooks/constants.ts
MIN_RECIPES = 5; MAX_RECIPES = 10; MAX_COOKBOOKS = 20; DEFAULT_SUBTITLE = 'Recipes I loved';
coverClasses(volume: number): string   // cycles cover-1..cover-4 by ((volume - 1) % 4) + 1; literal class strings
```

- Query keys: `['cookbooks', userId]`, `['likedRecipes', userId, page]`.
- CSS tokens in `@theme`, the same in both themes: `--color-cover-1: #263022; --color-cover-2: #7a3b2e; --color-cover-3: #2f4a57; --color-cover-4: #5a4a1f; --color-on-cover: #f0d9a8;`. Each pairing with `on-cover` is at least 6:1.

- [ ] **Step 1: Write the failing test**

- `/me?tab=cookbooks` selects the tab, which sits between Saved and AI recipes. No cookbook request is made before it opens.
- With 3 liked ids, Create is unavailable and the text "3 of 5 likes to make your first cookbook" shows.
- With at least 1 cookbook and fewer than 5 likes, the text is "3 of 5 likes to make a cookbook".
- With 5 liked ids, Create is a link to `/cookbooks/new`.
- With 20 cookbooks, Create is unavailable and "You can have up to 20 cookbooks." shows.
- Thumbnails show "Volume 2" and link to `/cookbooks/:id`.
- With no cookbooks, an empty message shows.
- The liked list shows cards and stubs.
- Unliking a card keeps it in the list with an empty heart, and focus stays on the heart.
- **Review Focus 2:** after the session switches to another user id, the first user's cookbooks and liked ids are not shown.
- `expectNoEmoji()`.

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/features/cookbooks src/features/profile/profileTabs.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement**

- Add `{ key: 'cookbooks', label: 'Cookbooks' }` to `TABS` after `saved`, plus the `cookbooks` prop and its ref, and update the doc comment.
- `LikedRecipesList` mirrors `SavedRecipesPanel`, with its own empty message ("Tap the heart on any recipe to like it") and the same stepping back from an empty later page.
- Create when unavailable is a non-link `Button` with `aria-disabled`, not `disabled`, and it is described by the message.

- [ ] **Step 4: Run the frontend regression commands**

Expected: all pass.

- [ ] **Step 5: Commit**

Message: `feat(frontend): Cookbooks tab with progress and liked recipes`

---

### Task 6: Pick page

**Files:**
- Create: `src/features/cookbooks/NewCookbookPage.tsx`, `src/features/cookbooks/PickGrid.tsx`
- Modify: `src/AppRoutes.tsx` (`/cookbooks/new` inside `ProtectedRoute`, registered before `/cookbooks/:id` from Task 7)
- Test: `src/features/cookbooks/newCookbook.test.tsx`

**Interfaces:**
- Consumes: `getLikedRecipes`, `createCookbook`, `MIN_RECIPES`, `MAX_RECIPES`, `DEFAULT_SUBTITLE`.
- Produces: the route `/cookbooks/new`. Query key `['likedPicker', userId]` (`useInfiniteQuery`). On success it invalidates `['cookbooks']` and navigates to `/cookbooks/{id}`.

- [ ] **Step 1: Write the failing test**

- Ticking shows chapter numbers in tick order. Unticking number 1 renumbers the others.
- The live line reads "2 of 5 to 10 chosen".
- Create stays unavailable at 4 and becomes available at 5.
- At 10, unticked boxes get `aria-disabled` and the line explains the limit.
- The choice survives "Load more".
- Stubs are not offered.
- With fewer than 5 selectable recipes, a message says only {n} of your liked recipes are available and at least 5 are needed.
- The POST body has `recipeIds` in tick order and a trimmed `subtitle`. A blank subtitle is left out.
- The subtitle field caps at 80 and has placeholder "Recipes I loved".
- A 409 shows `detail` in an alert, keeps the page open, keeps ticks that are still offered and reloads the list.
- Success navigates to the new book.
- `expectNoEmoji()`.

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/features/cookbooks/newCookbook.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement**

- Selection is an ordered `string[]` state. Each tile is a real `<input type="checkbox">` inside a `<label>`, with the photo, title and number badge.
- The live line has `aria-live="polite"`.
- Page size is `PAGE_SIZE`.

- [ ] **Step 4: Run the frontend regression commands**

Expected: all pass.

- [ ] **Step 5: Commit**

Message: `feat(frontend): choose liked recipes to make a cookbook`

---

### Task 7: The book reader

**Files:**
- Create: `src/features/cookbooks/CookbookPage.tsx` (data, URL page, keyboard, focus, delete)
- Create: `src/features/cookbooks/bookLeaves.tsx` (`CoverLeaf`, `OpeningLeaf`, `ChapterLeaf`, `StubChapterLeaf`)
- Create: `src/features/cookbooks/bookFacts.ts`
- Create: `src/features/recipe/RecipeInstructions.tsx`, extracted from `RecipeBody`
- Modify: `src/features/recipe/RecipeBody.tsx` (use the extracted component), `src/AppRoutes.tsx` (`/cookbooks/:id` in `ProtectedRoute`)
- Test: `src/features/cookbooks/cookbookPage.test.tsx`, `src/features/cookbooks/bookFacts.test.ts`

**Interfaces:**
- Consumes: `getCookbook`, `deleteCookbook`, `coverClasses`, `DEFAULT_SUBTITLE`, `ingredientLine`, `ConfirmDialog`, `cuisinesQueryKey`, `getCuisines`, `humanizeKey`.
- Produces:

```ts
// bookFacts.ts
bookFacts(chapters: SavedItem[], labelOf: (key: string) => string):
  { cuisines: string[]; totalMinutes: number }   // distinct labels in chapter order; nulls skipped
formatMinutes(n: number): string                 // 45 -> "45 min", 95 -> "1 h 35 min", 120 -> "2 h"
leafFromParam(raw: string | null, leafCount: number): number   // 1-based; anything invalid gives 1
```

- Query key: `['cookbook', id, userId]`.
- Leaf order: cover = 1, opening = 2, chapters = 3 and up. A book with no chapters has only the cover leaf, an empty message and no page controls.

- [ ] **Step 1: Write the failing `bookFacts.test.ts`**

- Distinct labels; stubs ignored; null minutes skipped.
- `formatMinutes` values as above.
- **Review Focus 3:** `leafFromParam` returns 1 for `null`, `abc`, `0`, `-1`, `1.5` and a value above `leafCount`, and returns `3` for `"3"` when `leafCount` is at least 3.

- [ ] **Step 2: Write the failing `cookbookPage.test.tsx`**

- The cover shows "The cookbook of", "Jane Doe", "Volume 2" and the subtitle, or "Recipes I loved" when null.
- The opening shows "Collected by Jane, July 2026", the cuisine labels, the total time and contents links to `?page=3` and so on.
- A chapter shows "Chapter 1", the title, the credit, ingredients, method and a link to the recipe page.
- A recipe with only `instructions` text shows it. A recipe with no `image` shows the title on a tint.
- A stub chapter shows the approved title and "no longer available" or "being reviewed".
- Next, Previous and "Page 3 of 7" update the URL, and Back returns to the previous leaf.
- Turning moves focus to the new leaf's heading.
- ArrowRight and ArrowLeft turn pages, with no wrap at the ends.
- **Review Focus 4:** arrow keys do nothing while `ConfirmDialog` is open, when focus is in an input, or with a modifier key held.
- **Review Focus 5:** with all chapters as stubs, the opening has no cuisines or total time, the contents still lists titles, and nothing throws.
- With no chapters, the empty message and Delete show, with no page controls.
- Delete confirms, sends DELETE, invalidates `['cookbooks']` and returns to `/me?tab=cookbooks`.
- A 404 shows a not-found message.
- All leaves are in the DOM, with inactive ones carrying `hidden`, and images are not `loading="lazy"`.
- `expectNoEmoji()`.

- [ ] **Step 3: Run to verify they fail**

Run: `npx vitest run src/features/cookbooks/cookbookPage.test.tsx src/features/cookbooks/bookFacts.test.ts`
Expected: FAIL.

- [ ] **Step 4: Implement**

- **Leaf markup needs two elements.** The outer leaf holds visibility (`hidden print:block` when inactive, `block` when active). The inner sheet holds layout (`md:grid md:grid-cols-2 print:block`). Keeping them apart stops `hidden` and `md:grid` conflicting on one element.
- **Sheet styling:** `bg-surface`, `border border-rule`, `rounded-card`, with `md:divide-x divide-rule`. Headings use `font-serif`. Small labels are uppercase and tracked. A leaf has no fixed height, so long recipes grow.
- **Heading focus:** each leaf has a heading with `tabIndex={-1}` and a ref. An effect moves focus when the page changes, skipping the first render.
- **Page turn:** add an `animate-leaf-in` token to `@theme` with keyframes on `opacity` and `translate` only, used as `motion-safe:animate-leaf-in`.
- **Keyboard:** one `keydown` listener on `window`, returning early if `deleteOpen`, if a modifier is held, or if `event.target` is inside `input, textarea, select, [contenteditable], [role="dialog"]`.
- **Date:** "Collected by" uses `Intl.DateTimeFormat('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' })` on `createdAt`.
- **Chapter pieces:** the credit mirrors `RecipeBody` ("by {name}" linking to the user, or "From {sourceName}"). Extract `RecipeInstructions` from `RecipeBody` unchanged in behaviour, so steps and the text fallback stay in one place. The chapter's ingredient list is plain `<ul>` lines through `ingredientLine`, without `RecipeBody`'s tick boxes.
- **Photo:** `recipe.image` with `alt=""` and no lazy loading. It is decorative, because the title is on the facing page.

- [ ] **Step 5: Run the frontend regression commands**

Expected: all pass, including existing `RecipeBody` and detail tests.

- [ ] **Step 6: Commit**

Message: `feat(frontend): read a cookbook as a small book`

---

### Task 8: Printing, browser check and dev log

**Files:**
- Modify: `src/index.css` (the print block), `src/components/AppShell.tsx` (`print:hidden` on header and footer), `src/features/cookbooks/CookbookPage.tsx` (Print button, `print:hidden` on controls), `src/features/cookbooks/bookLeaves.tsx` (`break-before-page`)
- Modify: `docs/plateful-frontend-dev-log.txt` (new PART 22, 80 columns at most, CRLF)
- Test: additions to `src/features/cookbooks/cookbookPage.test.tsx`

**Interfaces:**
- Consumes: the reader from Task 7. Nothing later depends on this task.

- [ ] **Step 1: Write the failing tests**

- A Print button calls `window.print` (stubbed).
- It has `type="button"`, and the page controls and Print carry `print:hidden`.
- Every leaf after the first carries `print:break-before-page`.

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/features/cookbooks/cookbookPage.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `index.css`, after the dark block:

```css
@media print {
  :root, :root[data-theme='dark'] {
    /* every colour token set back to its light @theme value */
  }
}
```

List all the tokens with their light values, including `color-scheme: light`. In print, links show as plain text (`print:no-underline`), and the "open recipe page" link is `print:hidden`.

- [ ] **Step 4: Run the frontend regression commands**

Expected: all pass.

- [ ] **Step 5: Browser check**

- Start the frontend through `.claude/launch.json` with `preview_start`, creating the entry if needed.
- The signed-in screens need the backend running with V12 applied. Ask the user to restart it and to sign in themselves in the browser pane. Never type their password.
- At widths 375 and 1280, in light and dark, check: heart on cards and recipe page, a full like, create, read and delete, keyboard turning, and focus.
- Check no sideways scrolling and no console errors.
- Check print with `window.matchMedia` where the pane allows it. Otherwise ask the user to open the browser's print preview and confirm that every leaf prints on its own page in light colours.
- Record exactly what was and was not checked.

- [ ] **Step 6: Write dev log PART 22 and commit**

PART 22 covers what was built, the decisions from spec section 2, test totals, the browser check result and the user's steps (restart the backend for V12, then run `RecipeLikeRepositoryDbTest` and `CookbookRepositoryDbTest` with the DB password set).

Check it has at most 80 columns and CRLF line endings. Stage only the files above. Message: `feat(frontend): print a cookbook; dev log PART 22`
