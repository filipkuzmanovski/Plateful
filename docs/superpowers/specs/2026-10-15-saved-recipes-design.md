# Saved recipes: design

Date: 2026-10-15
Status: approved in conversation; this written spec is awaiting review.

## 1. Goal

Signed-in users can bookmark recipes they want to cook, then find them again on their own profile page.

- Saving works from every recipe card and from the recipe page.
- The saved list is private: only its owner ever sees it.

## 2. Decisions from the conversation

| Question | Decision |
|---|---|
| Visibility | Private. Shown only on the user's own profile (`/me`), never on public profiles. |
| Where to save | On the recipe page (a Save / Saved button) AND on every recipe card (a bookmark in the corner). |
| Profile layout | Two tabs, "My recipes" and "Saved", with the tab kept in the URL as `/me?tab=saved`. |
| Saved state on cards | Load the user's saved recipe IDs once and cache them (approach 1). The public browse API stays unchanged. |

## 3. Backend

### 3.1 Schema: Flyway `V4__saved_recipes.sql`

```sql
CREATE TABLE saved_recipes (
    user_id    UUID NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    recipe_id  UUID NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, recipe_id)
);
CREATE INDEX idx_saved_recipes_user_created ON saved_recipes (user_id, created_at DESC);
```

- A user can save a recipe at most once (the primary key).
- Deleting a recipe or a user removes the matching rows (cascade).
- The plan must check that the existing tables are named `users` and `recipes`, with `id UUID`, and adjust if not.

### 3.2 Persistence

- **Entity `SavedRecipe`** (`model/entities`):
  - a composite id `(userId, recipeId)`;
  - a `@ManyToOne(fetch = LAZY)` `Recipe`;
  - `createdAt`, set by the database.
- **Repository `SavedRecipeRepository`:**
  - `save`: a native `INSERT ... ON CONFLICT (user_id, recipe_id) DO NOTHING`, so saving twice is harmless and there's no race;
  - `deleteByUserIdAndRecipeId`;
  - a page of recipe ids or recipes ordered by `created_at DESC, recipe_id` (the tie-breaker keeps paging stable);
  - `findRecipeIdsByUserId` (all ids, newest first).

### 3.3 Service: `SavedRecipeService`

- **`save(userId, recipeId)`:** 404 if the recipe doesn't exist (the existing `ResourceNotFoundException`). Otherwise the insert above. Idempotent.
- **`unsave(userId, recipeId)`:** deletes if present. Idempotent: no error if it wasn't saved.
- **`list(userId, pageable)`:** a `Page<RecipeDisplayDto>`, newest saved first.
  - It reuses `RecipeService`'s existing batched page assembly (photos, ratings, comment counts in a fixed number of queries), so there's no new N+1.
  - The page order follows the saved order, not recipe creation.
  - The plan picks how: load the ordered id page, then assemble DTOs in that order.
- **`ids(userId)`:** a `List<UUID>`.

### 3.4 API: `SavedRecipeController` (all authenticated; identity from `@CurrentUserId`)

| Method and path | Result |
|---|---|
| `PUT /api/users/me/saved/{recipeId}` | 204, or 404 if the recipe doesn't exist |
| `DELETE /api/users/me/saved/{recipeId}` | 204 (even if it wasn't saved) |
| `GET /api/users/me/saved?page=&size=` | 200, a `Page<RecipeDisplayDto>` in the same JSON shape as browse |
| `GET /api/users/me/saved/ids` | 200, `string[]` |

**Security config:**
- These paths are not public. `GET /api/users/*` matches a single segment only, so `/api/users/me/saved` already falls under "authenticated". The plan verifies this with a test.
- Anonymous callers get 401.

### 3.5 Two-factor rule widened (security fix)

`MfaEnforcementFilter` currently guards GET requests only when the decoded path ENDS with `/me`. The saved endpoints (`/api/users/me/saved`, `/api/users/me/saved/ids`) would slip past, so a half signed-in (`aal1`) session could read a private list.

The new rule: guard when the method is not GET, OR the decoded path's segments contain exactly `me`. Split on `/` and compare whole segments, so `/api/users/me`, `/api/users/me/saved` and `/api/recipes/{id}/ratings/me` are all guarded, but a path like `/api/users/medium` is not.

The existing behaviour for every currently guarded and unguarded route is unchanged. Tests pin that.

## 4. Frontend

### 4.1 API and cache (`src/api/saved.ts`)

- The functions:
  - `getSavedIds(): Promise<string[]>`
  - `getSavedRecipes(page): Promise<Page<RecipeDisplay>>`
  - `saveRecipe(id)`
  - `unsaveRecipe(id)`
- The hook `useSavedIds()`:
  - uses the React Query key `['savedIds', userId]`;
  - is enabled only when the auth status is `'ready'`;
  - returns `{ isSaved(id): boolean, toggle(id): void, isLoading }`.
- **The toggle is optimistic:**
  1. It updates the cached id list immediately.
  2. It calls the API.
  3. On error, it rolls back and shows a toast: "Couldn't update saved recipes. Please try again."
  4. On success, it invalidates `['savedRecipes']`, so the profile tab refreshes.
- **Signed out:** calling `toggle` navigates to `/login` with `state.from` set to the current location, and the recipe isn't saved. After sign-in the user returns to where they were.

### 4.2 Card bookmark (`RecipeCard`)

- A small round button in the top-right corner of the card photo:
  - `bg-paper/90`, a soft shadow, an olive icon;
  - lucide `Bookmark`, filled with saffron when saved.
- **Accessible name:** "Save {title}" or "Remove {title} from saved", with `aria-pressed`.
- **Clicking it doesn't open the recipe.** The card is a link, so the button calls `preventDefault` and `stopPropagation`, and it's a separate focusable `<button>` that isn't nested inside the `<a>`. The plan restructures the card if it's currently a single `<a>`.
- **Touch target:** at least 40px.

### 4.3 Recipe page button (`RecipeDetailPage`)

- A button near the title actions: "Save" with an outline bookmark, or "Saved" with a filled bookmark and a saffron tint.
- `aria-pressed`. It uses the same `useSavedIds` toggle.

### 4.4 Profile tabs (`MyProfilePage`)

- A tab list with "My recipes" and "Saved" (ARIA tabs: `role=tablist/tab/tabpanel`, arrow-key navigation).
- The selected tab comes from `?tab=` (`saved`, or the default `mine`). Switching updates the URL with `replace`.
- **The Saved tab:**
  - `getSavedRecipes` in the same card grid and pagination as browse, newest saved first;
  - **empty state:** "No saved recipes yet. Tap the bookmark on any recipe to save it here." plus a "Browse recipes" button;
  - unsaving a card here removes it from the grid once the toggle succeeds.
- Public profiles (`/users/:id`) are unchanged and never show saved recipes.

### 4.5 Style

Golden Hour: secondary text `text-olive/70`, no emojis, buttons for clickable things.

## 5. Testing

**Backend:**
- **Service and controller:**
  - save gives 204, and saving twice is still 204 with one row;
  - unsave gives 204 even when not saved;
  - saving a missing recipe gives 404;
  - list is newest saved first, with paging;
  - ids returns only the caller's ids (user A can't see user B's);
  - anonymous gives 401.
- **MFA filter:**
  - `GET /api/users/me/saved` and `/api/users/me/saved/ids` with `aal1` and a factor give 401 `mfa_required`;
  - a GET to `/api/users/medium` (or another single public path) isn't guarded;
  - all existing filter tests stay green.
- **Real-DB test**, with the same annotations as the other DB tests, writing only inside the rolled-back transaction:
  - the ON CONFLICT insert is idempotent;
  - deleting a recipe cascades;
  - the ordering is right.

**Frontend:**
- the card bookmark toggles: the API is called, `aria-pressed` flips, and the card link isn't followed;
- rollback and a toast on API failure;
- signed out, the bookmark navigates to `/login` with `from`;
- the recipe page Save becomes Saved;
- the profile tabs follow the URL (`?tab=saved`), with arrow keys;
- the Saved tab lists recipes;
- the empty state;
- unsaving from the Saved tab removes the card;
- a public profile has no Saved tab;
- `expectNoEmoji()`.

## 6. Out of scope

- Public or shared collections.
- Folders or lists of saves.
- "Saved by N people" counts.
- Notes on saved recipes.
- Offline support.
