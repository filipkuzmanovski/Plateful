# Likes and liked cookbooks: design

Date: 2026-11-05
Status: approved in conversation; this written spec is awaiting review.

This is sub-project 1 of 3. Sub-project 2 is calorie estimates and sub-project 3 is the preference cookbook. Section 9 records what was agreed about them; each gets its own spec, plan and build.

## 1. Goal

Signed-in users can publicly like published recipes, then choose some of the recipes they liked and turn them into a personal cookbook: a small book with their name on the cover that they can read, keep and print.

**The feature works when:**
- anyone can see how many likes a recipe has, on its card and its page;
- a user with at least 5 likes can create a cookbook from 5 to 10 of them;
- opening a cookbook feels like a book, not another list of cards, and it prints cleanly;
- removed recipes never survive inside a cookbook;
- nothing here calls an AI provider.

## 2. Decisions from the conversation

| Question | Decision |
|---|---|
| Likes and Saved | Separate. A like is public and means "I enjoyed this". Saved stays a private "keep for later" list. |
| What is public now | Only the like count. Likes are stored per user so later social features can show who liked what. |
| What can be liked | Published (approved) recipes only. AI recipes are private, so they have no likes. |
| Who can like | Signed-in users, including on their own recipes, matching ratings. |
| How a cookbook is made | By the user, never automatically. The Create button becomes active at 5 likes. |
| Choosing recipes | The user ticks 5 to 10 liked recipes. Tick order is chapter order. A recipe may appear in several cookbooks. |
| Stored or generated | Stored. Each cookbook keeps its own recipe list. |
| Title | The user's name: "The cookbook of Ana Petrovska". Each book also has a volume number and an optional subtitle, shown as "Recipes I loved" when left empty. |
| Look | The keepsake book, chosen from three mockups: a cloth-style cover, then page turns with one chapter per recipe. |
| Unliking later | The recipe stays in existing cookbooks. |
| Live recipes or copies | Cookbooks point at recipes and do not copy them, so authors and admins stay in control. |
| Cookbook privacy | Private to the owner for now. |

## 3. Data: Flyway `V12__likes_and_cookbooks.sql`

```sql
CREATE TABLE recipe_likes (
    user_id    UUID NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    recipe_id  UUID NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, recipe_id)
);
CREATE INDEX idx_recipe_likes_user_created ON recipe_likes (user_id, created_at DESC);
CREATE INDEX idx_recipe_likes_recipe ON recipe_likes (recipe_id);

CREATE TABLE cookbooks (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind       TEXT NOT NULL DEFAULT 'liked' CHECK (kind IN ('liked')),
    volume     INTEGER NOT NULL CHECK (volume > 0),
    subtitle   VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, volume)
);

CREATE TABLE cookbook_recipes (
    cookbook_id UUID NOT NULL REFERENCES cookbooks(id) ON DELETE CASCADE,
    recipe_id   UUID NOT NULL REFERENCES recipes(id)   ON DELETE CASCADE,
    position    SMALLINT NOT NULL CHECK (position > 0),
    PRIMARY KEY (cookbook_id, recipe_id),
    UNIQUE (cookbook_id, position)
);

ALTER TABLE recipe_likes     ENABLE ROW LEVEL SECURITY;
ALTER TABLE cookbooks        ENABLE ROW LEVEL SECURITY;
ALTER TABLE cookbook_recipes ENABLE ROW LEVEL SECURITY;
```

- RLS is on with no policies, as with `saved_recipes`, so the public API key cannot read or change these rows. The backend's own connection is unaffected.
- `kind` holds only `liked` now. Sub-project 3 will add a value, and a later social feature can add another, without new tables.
- `position` gaps after a recipe is deleted are harmless, because chapters are ordered by `position` and numbered as they are shown.

## 4. Backend

### 4.1 Rules

**Liking**
- `like(userId, recipeId)` is idempotent. The recipe must exist and be visible to the user under `RecipeVisibility`, or the existing visibility errors apply.
- A recipe the user can see but that is not approved (their own pending recipe, for example) gets the same `409` as ratings and comments, through `RecipeVisibility.requireApprovedForInteraction`. The frontend hides the heart there, so people do not meet this message.
- A recipe deleted while the like is in flight gets `404`, not `500`. The insert selects from `recipes`, so a missing recipe inserts nothing.
- `unlike` is idempotent and always allowed.
- A recipe's likes are kept while it is under review, and they count again when it returns to approved.

**The count**
- `RecipeDisplayDto` gains `long likeCount`. It is public on every read.
- List pages get it from one more batched query in `RecipeService.toDisplayPage`, in the same way as comment counts. A recipe absent from the result has zero likes. The single-recipe read gets it from one count query.

**Creating a cookbook** (one short transaction, no AI)
1. Trim the subtitle. Blank becomes null. Reject it over 80 characters or with control characters (`400`).
2. Require 5 to 10 distinct recipe ids (`400`).
3. Lock the user's row (`PESSIMISTIC_WRITE`). This makes the cap and the volume number safe when two creates arrive at once. `UNIQUE (user_id, volume)` is the backstop.
4. Require fewer than 20 existing cookbooks, or `409`, "You can have up to 20 cookbooks."
5. Require every recipe to be liked by the user and approved, or `409`, "Some of these recipes are no longer available." Nothing is created.
6. Set `volume` to the user's highest volume plus 1. A gap left by deleting a middle volume stays. Deleting the newest volume frees its number for the next book.
7. Insert the cookbook and its recipes, with `position` following the request order.

**Reading a cookbook**
- The owner gets the cookbook. Anyone else gets `404`, the same as a missing id.
- Chapters come back ordered by `position`, assembled in one batch through `RecipeService.toDisplayPage`.
- Each chapter reuses `SavedItemDto`: a full recipe when `RecipeVisibility.canSee` allows it, otherwise a stub with the last approved title and the state `under_review` or `unavailable`.
- A deleted recipe has already gone, through the cascade. The rare recipe that is neither visible nor ever approved is skipped.
- The owner's first and last names come from the live `users` row.

### 4.2 API (authenticated; identity from `@CurrentUserId`)

| Method and path | Result |
|---|---|
| `PUT /api/users/me/likes/{recipeId}` | 204; idempotent |
| `DELETE /api/users/me/likes/{recipeId}` | 204; idempotent |
| `GET /api/users/me/likes` | Page of `SavedItemDto`, newest like first, with the same stub rules as Saved |
| `GET /api/users/me/likes/ids` | Every liked recipe id |
| `GET /api/users/me/cookbooks` | List of `CookbookSummaryDto`, newest first |
| `POST /api/users/me/cookbooks` | 201 with `CookbookSummaryDto`; body `{ recipeIds: UUID[], subtitle?: string }` |
| `GET /api/users/me/cookbooks/{id}` | `CookbookDto` |
| `DELETE /api/users/me/cookbooks/{id}` | 204; 404 when it is not the caller's |

```java
record CookbookSummaryDto(UUID id, int volume, String subtitle, OffsetDateTime createdAt, int recipeCount) {}
record CookbookDto(UUID id, int volume, String subtitle, OffsetDateTime createdAt,
                   String ownerFirstName, String ownerLastName, List<SavedItemDto> chapters) {}
```

The existing MFA filter already guards every path with a `me` segment, and nothing there needs changing.

## 5. Frontend: likes

- `src/api/likes.ts` and `useLikedIds`, following `useSavedIds`, with query key `['likedIds', userId]`.
- **On cards:** a `LikeButton` is a sibling of the card link, never inside it, placed over the photo at top left, opposite the bookmark. The Video badge moves to the bottom left of the photo to make room. It is a heart plus the count, at least 40 px tall. Its accessible name is "Like {title}" or "Remove like from {title}", and the count is announced with it, such as "12 likes".
- **On the recipe page:** a larger heart and count beside Save.
- The heart appears only on approved recipes. Stub cards have none.
- **Signed out:** a click goes to sign-in, the same as the bookmark.
- **Behaviour:** the heart and count change at once. On success, cached recipe lists and the recipe page are patched so every card agrees. On failure, both go back and a short toast appears.
- While a request is pending the button uses `aria-disabled`, not `disabled`, so it keeps keyboard focus.
- **Privacy page:** add a line saying likes count publicly and the people behind them may be shown publicly in a later version, so that change surprises nobody.

## 6. Frontend: creating a cookbook

**The Cookbooks tab** (`/me?tab=cookbooks`, between Saved and AI recipes) shows, in order:
1. A row of cover thumbnails, or a short message when there are none.
2. The Create button. Below 5 likes it is unavailable, with "3 of 5 likes to make your first cookbook". It is also unavailable at 20 cookbooks, with a message saying so.
3. The liked recipes, as cards and stubs with pagination, reusing `SavedRecipesPanel`'s pattern. Unliking does not remove the card straight away. It stays with an empty heart until the list next reloads, so keyboard focus is not lost and a mistake can be undone.

**The pick page** (`/cookbooks/new`, sign-in required):
- Liked recipes appear as a grid of checkboxes, each with a photo and title. Stubs are left out.
- Ticked recipes show their chapter number. Unticking renumbers the rest.
- A live line reads "2 of 5 to 10 chosen". At 10, the other boxes go `aria-disabled` and the line explains why.
- The choice is kept across "Load more".
- An optional subtitle field, up to 80 characters, has the placeholder "Recipes I loved".
- Create is unavailable until 5 are chosen.
- A `409` shows its message, keeps the page open and reloads the list. On success, the user lands on the new book's cover.

## 7. Frontend: the book (`/cookbooks/:id`)

### 7.1 Structure

The book is a sequence of leaves, and the current leaf is in the URL (`?page=N`, 1-based). A missing or out-of-range value means the cover. Refresh and the back button return to the same place.

| Leaf | Contents |
|---|---|
| Cover | "The cookbook of", the owner's name, "Volume N", the subtitle |
| Opening | Left: "Collected by Ana, November 2026", the cuisines in the book and the total ready-in time. Right: contents, with each entry linking to its chapter. |
| One per chapter | Left: the recipe's cover photo, large. Right: "Chapter N", title, author or source credit, ingredients and method, and a link to the recipe page. |
| Stub chapter | "Chapter N", the last approved title and a short "no longer available" or "being reviewed" note. |

- **Worked out in the frontend:** a small pure function produces the opening's facts. Cuisines are the distinct labels from the visible chapters. Total time adds their `readyInMinutes`, skipping nulls, and is left out when the total is zero. No AI is involved.
- **No photo:** the left page shows the title large on a tint instead.
- **Empty book:** with no chapters left, the book is the cover plus a short message and the delete button.

### 7.2 Layout

- **Wide screens** show two pages side by side on one sheet, with a rule between them. **Phones** stack the same two pages into one column. It is one model and one route, responsive through CSS.
- **Long recipes:** a leaf grows to fit its content. Nothing is cut off and pages do not scroll on their own.
- **Cover colour:** it comes from the volume number, cycling through 4 cover colours. These are new role-named tokens in `src/index.css` (`cover-1` to `cover-4` and `on-cover`), the same in both themes. Each pairing must reach at least 4.5:1 contrast.
- **Book styling:** the book uses Newsreader, the existing page, surface and rule tokens, and small uppercase labels. No emojis.

### 7.3 Moving through the book

- Previous and Next buttons sit under the sheet, with "Page 3 of 7".
- Left and Right arrow keys also turn pages, unless focus is in a form control or a modifier key is held.
- Turning a page moves focus to the new leaf's heading, which has `tabindex="-1"`.
- The turn is a short fade and slide using `translate` and `opacity`, inside `motion-safe:`. There is no 3D page flip and no animation library.
- A Delete button opens the existing `ConfirmDialog`. On success, the user returns to the Cookbooks tab.

### 7.4 Printing

- A Print button calls `window.print()`. The browser's dialog also offers Save as PDF.
- All leaves are always in the page, with inactive ones hidden on screen and shown in print (`hidden print:block`), so pressing Ctrl+P works too. Photos load eagerly so they appear in print.
- Print styles:
    - The app header, footer and book controls are hidden.
    - Each leaf starts a new page.
    - The light token values are forced even in dark mode.
    - Links print as plain text.

## 8. Testing and errors

**Backend** (`-Dtest=...` filtered, never unfiltered)
- Unit tests for the service rules: every validation in 4.1, the cap, volume numbering and non-owner `404`.
- Controller tests for each route and status.
- DbTests for: the idempotent like, the cascade from a deleted recipe into `recipe_likes` and `cookbook_recipes`, `UNIQUE (user_id, volume)`, chapter order and the like count query. No `@Import` in DbTests, per the existing lesson about extra connection pools.

**Frontend** (Vitest)
- `LikeButton`: states, signed-out sign-in, optimistic change and failure rollback.
- The pick page: order, renumbering, the 5 and 10 limits, selection kept across pages, and `409` handling.
- The book: leaf from the URL, keyboard turning and focus, stub and empty chapters, and the opening's facts.
- The tab: the progress line and the cap message.
- `expectNoEmoji()` on each new screen.

**Browser check:** both widths, both themes, the print preview and one full like, create, read and delete. Signed-in screens need the user to sign in themselves in the browser pane.

**User steps:** restart the backend so V12 runs, then run the new DbTests against the live database.

**Dev log:** add PART 22 to `docs/plateful-frontend-dev-log.txt` (80 columns at most, CRLF).

## 9. Out of scope, and the agreed direction for what follows

**Not in this sub-project:** sorting browse by most liked, public "liked by" lists, cookbooks from other people's likes, sharing a cookbook, renaming a cookbook after it is made.

**Sub-project 2, calorie estimates** (recommended direction, to be settled in its own design):
- The AI gives grams, calories and macros per ingredient in a fixed format. Our code adds them up, divides by servings and rejects impossible answers.
- Numbers are shown rounded with an "AI estimate" label.
- Accuracy is measured first by running the estimator over imported recipes that already have values.
- Still open: when estimates run, how they are stored next to imported values, and what to do with user recipes that have no servings.

**Sub-project 3, preference cookbook:**
- The user wants 5 recipes, to save tokens.
- It reuses the `cookbooks` tables, with a new `kind` and a way to point at private `generated_recipes`. Those rows must then be protected from the 30-day cleanup.
- Five recipes are unlikely to fit one AI call within Groq's free per-minute token limit, so it probably needs small batches. The limit should be re-checked and tested at design time.
- Still open: how it counts against the 10 daily generations.
