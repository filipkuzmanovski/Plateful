# Admin review: design

Date: 2026-10-18
Status: approved in conversation; this written spec is awaiting review.

## 1. Goal

Nothing a user posts becomes public until an admin has checked it.

- New recipes start as pending. An admin approves or rejects each one from a review queue.
- An approved recipe that its author edits goes back to the queue.
- Authors get an email when their recipe is approved, or when it is rejected (with the reason).
- Everything public today (the 206 imported recipes and the existing user recipes) stays public.

## 2. Decisions from the conversation

| Question | Decision |
|---|---|
| Who is an admin | An `is_admin` flag on `users`, set only by hand with SQL. There is no admin-management screen. |
| Admin security | Admins must have two-factor turned on, and every admin action needs an aal2 session. |
| Edits to approved recipes | They go back to pending and are hidden until re-approved (option A). Comments, ratings and saves are kept. |
| Visibility while re-reviewed | Only in the Saved tab, as a stub card: last approved title, placeholder image, "Under review" badge. Opening it shows a "Check back soon" page. |
| Rejection | The reason is required. The author sees it on the recipe and in My recipes, and can edit (resubmit) or delete. |
| Emails | Sent for both approval (a congratulations line) and rejection (the reason), from a dedicated Gmail account over SMTP. Until the Gmail settings exist, emails go to the backend log. |
| Admin UI | A "Review queue" header link (admins only). It opens a queue page, and each item opens a review page showing the full recipe with Approve and Reject. |

## 3. Data model

### 3.1 Flyway `V6__admin_review.sql`

```sql
ALTER TABLE users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE recipes
    ADD COLUMN status           TEXT NOT NULL DEFAULT 'pending'
                                CHECK (status IN ('pending', 'approved', 'rejected')),
    ADD COLUMN rejection_reason VARCHAR(500),
    ADD COLUMN approved_title   VARCHAR(255),
    ADD COLUMN ever_approved    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN submitted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN reviewed_at      TIMESTAMPTZ,
    ADD COLUMN reviewed_by      UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN revision         INTEGER NOT NULL DEFAULT 0;

-- Everything that exists today stays public.
UPDATE recipes
   SET status = 'approved', approved_title = title, ever_approved = true, reviewed_at = now();

CREATE INDEX idx_recipes_pending_queue ON recipes (submitted_at) WHERE status = 'pending';
CREATE INDEX idx_recipes_status ON recipes (status);
```

What the new columns mean:

- **`status`:** `pending`, `approved` or `rejected`. The column defaults to `pending` for new rows; the service always sets it explicitly anyway.
- **`rejection_reason`:** set only while the status is `rejected`.
- **`approved_title`:** the title as of the most recent approval. The Saved stub card uses it.
- **`ever_approved`:** tells a brand-new recipe apart from an edit of a recipe that was approved before.
- **`submitted_at`:** when the recipe last entered `pending`. The queue is ordered by it, oldest first.
- **`reviewed_at` / `reviewed_by`:** the latest decision only. There is no history table.
- **`revision`:** incremented on every author change to the recipe's content. That includes edits to the fields, the ingredients and the photos. Approve and reject must quote it (section 4.5).

Other points:

- RLS is already on for `users` and `recipes`, and adding columns doesn't change that. The backend still connects as the table owner.
- The plan must confirm the current Flyway version is V5. If it isn't, it renumbers.

### 3.2 Entities and DTOs

- **`User`** gains `isAdmin` (read-only from the app's point of view).
  - No request DTO carries it, so `PUT /api/users/me` can never set it.
  - The public `UserDisplayDto` does not expose it.
- **`Recipe`** gains the fields above, as an enum `RecipeStatus { PENDING, APPROVED, REJECTED }` mapped to the lowercase text.
- **`GET /api/users/me`** returns a new `MyProfileDto`: the existing `UserDisplayDto` fields plus `isAdmin` and `rejectedCount` (how many of the user's recipes are `rejected`). Public `GET /api/users/{id}` keeps `UserDisplayDto`. `POST` and `PUT /api/users/me` return `MyProfileDto` too, so the cached profile keeps one shape.
- **`RecipeDisplayDto`** gains a nullable `review` block. It is present only when the viewer is the author or an admin:
  - `status`;
  - `rejectionReason`;
  - `everApproved`;
  - `revision`;
  - `submittedAt`.

  For everyone else the block is `null`. Those viewers only ever receive approved recipes, plus the stubs below.
- **`SavedRecipeStubDto`:** `{ id, approvedTitle, state: "under_review" | "unavailable" }`.

## 4. Backend

### 4.1 Status rules (`RecipeService`)

| Event | Result |
|---|---|
| A non-admin creates a recipe | `pending`, `submitted_at = now()` |
| An admin creates a recipe | `approved`, `ever_approved = true`, `approved_title = title`, `reviewed_by = self`, `reviewed_at = now()` |
| A non-admin author edits (any content change, including photos) | `revision + 1`. If it was `approved` or `rejected`, it becomes `pending` with `submitted_at = now()`. If it was already `pending`, it stays `pending` and `submitted_at` is unchanged. `rejection_reason` is cleared. |
| An admin edits their own recipe | `revision + 1`, then it is approved as for an admin create |
| An admin approves | `approved`, `ever_approved = true`, `approved_title = title`, `rejection_reason = null`, `reviewed_*` set, approval email queued |
| An admin rejects (reason 1–500 chars, trimmed, required) | `rejected`, `rejection_reason` set, `reviewed_*` set, rejection email queued |

- **Who can edit:** only the owner can edit or delete, as today. Admins do not edit other people's recipes.
- **Admin rights:** an admin's own recipes skip the queue because the author is an admin. That is the only special case.

### 4.2 One visibility rule

A single specification and predicate, `RecipeVisibility`, decides every read.

**Visible to viewer V** means: `status = 'approved'`, OR V is the author, OR V is an admin.

| Read | Rule |
|---|---|
| Browse and search (`GET /api/recipes`) | `approved` only, for everyone including admins and authors |
| Cuisine and sea counts | `approved` only |
| `GET /api/users/{id}/recipes` | `approved` only, unless V is that user or an admin; then all statuses, with the `review` block |
| `GET /api/recipes/{id}` | Visible: the full recipe. Not visible but `ever_approved`: 404 ProblemDetail with `approvedTitle` and `code: "under_review"` (status `pending`) or `code: "unavailable"` (status `rejected`). Not visible otherwise (never approved): the existing 404. |
| Comments, ratings, reactions: reads | 404 when the recipe is not visible to V |
| Comments, ratings, reactions: create, update, delete | The recipe must be `approved`. Not visible: 404. Visible but not approved: 409 with `code: "recipe_not_approved"`. |
| `PUT /api/users/me/saved/{id}` | 404 when the recipe is not visible to V. `DELETE` always works. |
| `GET /api/users/me/saved` | Visible recipes come back as normal cards. Saved recipes not visible to V come back as `SavedRecipeStubDto`: `under_review` when `ever_approved` and `pending`, `unavailable` when `ever_approved` and `rejected`. |
| `GET /api/users/me/saved/ids` | Unchanged: all saved ids |

About the Saved list:

- Saved recipes that were never approved can't appear, because they could never have been saved by a non-author. The query still filters them out defensively.
- The Saved page is a mixed list of cards and stubs.
  - **Shape:** each item is `{ kind: "recipe", recipe } | { kind: "stub", stub }`.
  - **Order:** the saved order is preserved.
  - **Page metadata:** same as today.
- **The rejected case:** the conversation covered only pending. A recipe rejected after an edit is also hidden from savers, so it gets the stub with the badge "Unavailable". Its page (the `unavailable` 404 above) reads "This recipe isn't available right now."

### 4.3 Optional viewer on public reads

- Public GET routes stay `permitAll`. Controllers learn the optional viewer through a new `@OptionalCurrentUserId UUID viewerId`:
  - null when there is no JWT;
  - the `sub` claim when a valid JWT is present.
- `@CurrentUserId` keeps its current required meaning.
- An invalid or expired token still gets 401, as today. The frontend already sends the token whenever a session exists.
- **Admin status:** looked up from `users.is_admin` per request. It is never read from the JWT.

### 4.4 Admin API: `AdminReviewController` at `/api/admin`

| Method and path | Result |
|---|---|
| `GET /recipes/pending?page=&size=` | `Page<RecipeDisplayDto>` (with `review`), oldest `submitted_at` first, tie-break `id` |
| `GET /recipes/pending/count` | `{ "count": n }` |
| `GET /recipes/{id}` | The full recipe with `review`, whatever its status (the review page uses it) |
| `POST /recipes/{id}/approve` | Body `{ "revision": n }`. 204. |
| `POST /recipes/{id}/reject` | Body `{ "revision": n, "reason": "..." }`. 204. |

Errors:

- **Not an admin:** 403 with `code: "forbidden"`, on every route.
- **Admin with no verified factor:** 403 with `code: "admin_mfa_setup_required"`.
- **Admin with a verified factor but an aal1 session:** 401 with `code: "mfa_required"`. That comes from the existing `MfaEnforcementFilter`, whose guard is widened so any path with an `admin` segment is guarded, GET included. The rule becomes: not GET, OR the segments contain `me` or `admin`.
- **The recipe is not `pending`:** 409 with `code: "not_pending"`.
- **The revision doesn't match:** 409 with `code: "recipe_changed"`.
- **The reason is missing or blank, or longer than 500:** 400 (validation).
- **No such recipe:** 404.

Implementation notes:

- The admin check plus the "has a verified factor" check live in one `AdminGuard` component. It reuses `MfaFactorLookup`.
- Approve and reject run in one transaction that locks the recipe row (`SELECT ... FOR UPDATE`, or a JPA pessimistic write lock). The status and revision checks happen under the lock.

### 4.5 Why `revision`

The admin reviews what the page showed them. If the author edits while the review page is open, approving would publish content nobody checked. So approve and reject quote the `revision` the page was rendered with. Any author change increments it (section 4.1), and a stale revision gets 409 `recipe_changed`.

### 4.6 Emails

- **Dependency:** `spring-boot-starter-mail`.
- **Configuration:**
  - `spring.mail.host=smtp.gmail.com`
  - `spring.mail.port=587`
  - STARTTLS on
  - `spring.mail.username=${MAIL_USERNAME:}`
  - `spring.mail.password=${MAIL_PASSWORD:}`
  - `plateful.frontend-url=${FRONTEND_URL:http://localhost:5173}`
- **Interface `ReviewEmailSender`** has two implementations:
  - an SMTP one, active when `MAIL_USERNAME` is non-blank;
  - a logging one otherwise. It logs the recipient, subject and body at INFO.
- **Trigger:** approve and reject publish a `RecipeReviewedEvent`. A `@TransactionalEventListener(phase = AFTER_COMMIT)` handles it, `@Async` on a small executor.
  - A failure is logged at WARN and never surfaces to the admin. There is no retry.
  - If the transaction rolls back, no email is sent.
- **Recipient:** the author's `users.email`. There is no email when:
  - the recipe has no author (imported recipes);
  - the author is the admin acting on their own recipe.
- **From:** `MAIL_USERNAME`, display name "Plateful".
- **Format:** plain text. Before building the subject, CR and LF characters are removed from the title (header-injection guard).
- **The approval email:**

  ```
  Subject: Your recipe "{title}" is live on Plateful

  Congratulations! Your recipe "{title}" has been approved and is now live on Plateful.

  View it: {frontendUrl}/recipes/{id}

  — The Plateful team
  ```

- **The rejection email:**

  ```
  Subject: Your recipe "{title}" needs changes

  Thanks for sharing "{title}" on Plateful. An admin reviewed it and couldn't approve it yet.

  Reason: {reason}

  You can edit the recipe and resubmit it here: {frontendUrl}/recipes/{id}/edit

  — The Plateful team
  ```

## 5. Frontend

### 5.1 Data

- **`useAuth`** exposes `profile.isAdmin` (from `MyProfileDto`).
- **`src/api/admin.ts`:**
  - `getPendingRecipes(page)`
  - `getPendingCount()`
  - `getReviewRecipe(id)`
  - `approveRecipe(id, revision)`
  - `rejectRecipe(id, revision, reason)`
- **Query keys:** `['admin', 'pending', page]`, `['admin', 'pendingCount']`, `['admin', 'recipe', id]`.
  - Approve and reject invalidate `['admin']`, `['recipes']`, `['recipe', id]`, `['userRecipes']` and `['savedRecipes']`.
- **Saved list API:** returns the mixed item shape (section 4.2), and `SavedRecipesPanel` renders both kinds.
- **Recipe page fetch:** a 404 with `code: "under_review"` or `code: "unavailable"` is handled as its own state (with `approvedTitle`), not as "not found".

### 5.2 Authors

- **After creating a recipe:**
  - for a non-admin, a toast reads "Submitted for review. We'll email you when it's approved.";
  - for an admin, the existing behaviour.
- **Recipe page banner,** for the author only, when the status is not approved:
  - **pending:** a saffron banner, "Waiting for review. Only you can see this recipe.";
  - **rejected:** a danger banner, "This recipe wasn't approved." plus the reason, plus an "Edit and resubmit" link button to `/recipes/{id}/edit`.
- **Comments, ratings and reactions** are not rendered on a recipe that isn't approved.
- **The edit form,** for a non-admin editing a recipe whose status is approved or rejected, shows a notice above Save: "Saving changes sends this recipe back for review. It will be hidden from others until it's approved."
- **My recipes cards** get a "Pending review" (saffron) or "Rejected" (danger) badge.
  - When at least one is rejected, the tab label reads "My recipes · {n} needs attention", where n is `MyProfileDto.rejectedCount` (the author's recipes with status `rejected`), so it is correct across pages.

### 5.3 Everyone else

- **Saved stub card:**
  - a placeholder image block (no photo);
  - the `approvedTitle`;
  - a badge reading "Under review" or "Unavailable";
  - a link to `/recipes/{id}`;
  - the bookmark button still works, so the recipe can be unsaved.
- **The `under_review` recipe page** shows "This recipe is being reviewed after an edit. Check back soon." along with the approved title as a heading and the Save/Saved button.
- **The `unavailable` recipe page** (404 `code: "unavailable"`) shows "This recipe isn't available right now." with the approved title as a heading and the Save/Saved button.

### 5.4 Admins

- **Header:** a "Review queue" link, shown only when `profile.isAdmin`, with a count badge when the count is above 0. The count is polled with the query's normal staleness, with no websocket.
- **`/admin/review`** (an admin-only route; non-admins see "Page not found"):
  - each row shows the title, the author's name, the submitted time (relative), and a "Re-review" tag when `everApproved`;
  - oldest first, paginated;
  - empty state: "Nothing to review right now."
- **`/admin/review/:id`:**
  - the full recipe rendered with the same components as the public recipe page, without comments, ratings or reactions;
  - a sticky bottom bar with **Approve** and **Reject**.
    - **Reject** opens a Radix Dialog with a required textarea (1–500 chars, with a counter), "Cancel" and "Reject recipe".
    - **After success:** a toast ("Recipe approved" or "Recipe rejected"), then a move to the oldest remaining pending recipe, or to `/admin/review` when there is none.
    - **409 `recipe_changed`:** an inline message, "This recipe changed. Reload to review the latest version.", with a Reload button.
    - **409 `not_pending`:** "This recipe was already reviewed." with a link back to the queue.
- **Admin without two-factor** (403 `admin_mfa_setup_required`): both admin pages show a panel instead, "Set up two-factor authentication to use the review queue.", with a link to `/me` (the Two-factor section).
- **Admin with a factor at aal1:** this is already handled by the existing `needs-mfa` flow.

### 5.5 Style

Golden Hour tokens, with no emojis (`expectNoEmoji()`). Badges and banners use the existing saffron and danger tokens, and every action is a real `<button>`.

## 6. Testing

### Backend

- **DB tests,** with the same annotations as `RecipeCuisineQueryDbTest`, writing only inside rolled-back transactions:
  - after V6, all existing recipes are `approved`, with `approved_title = title` and `ever_approved = true`;
  - `RecipeVisibility` across browse, search, counts and the profile list, for each kind of viewer: anonymous, other user, author and admin. This uses throwaway users, as in `savesAreIsolatedPerUser`;
  - the queue order by `submitted_at`, with the `id` tie-break;
  - the saved list mixing cards and stubs, in saved order.
- **Service tests:**
  - every row of the section 4.1 table;
  - the revision mismatch gives 409;
  - not pending gives 409;
  - the reason is required and trimmed;
  - an event is published on approve and reject, and is not sent if the transaction fails;
  - admin create and edit are auto-approved.
- **Controller and security tests:**
  - 403 for non-admins on each `/api/admin` route;
  - 403 `admin_mfa_setup_required`;
  - 401 `mfa_required` for an aal1 admin with a factor (`MfaEnforcementFilterTest` gains the `admin` segment cases, GET included);
  - `PUT /api/users/me` ignores `isAdmin`;
  - comment, rating and reaction writes on non-approved recipes give 404 or 409;
  - `GET /api/recipes/{id}` for a non-visible, previously approved recipe gives 404 `under_review` or `unavailable` with `approvedTitle`;
  - `MyProfileDto.rejectedCount` counts only the caller's rejected recipes;
  - `@OptionalCurrentUserId` with and without a token.
- **Email tests:**
  - the exact subject and body of both emails;
  - CR and LF stripped from the subject;
  - the logging implementation is chosen when `MAIL_USERNAME` is blank;
  - no email for author-less or self-reviewed recipes.

### Frontend

- the author banners (pending and rejected with reason), the edit-form notice, the My recipes badges and the "needs attention" label;
- the Saved stub card (both states), and the `under_review` and `unavailable` recipe pages;
- the header link only for admins, with the count;
- the queue list and the empty state;
- the review page: approve, reject with the dialog (required, counter), moving to the next recipe, and the `recipe_changed` and `not_pending` messages;
- the admin two-factor setup panel;
- the create toast for non-admins;
- `expectNoEmoji()` throughout.

## 7. Rollout

1. V6 applies automatically on the next backend restart. Everything visible today stays visible.
2. The user makes themselves admin in the Supabase SQL editor:

   ```sql
   update users set is_admin = true where email = '<your email>';
   ```

3. The user's account must have two-factor turned on to use the queue.
4. **Optional:** the Gmail steps.
   1. Create a personal Gmail account for Plateful.
   2. Turn on 2-Step Verification.
   3. Create an app password at myaccount.google.com/apppasswords.
   4. Add `MAIL_USERNAME` and `MAIL_PASSWORD` to the IntelliJ run configuration.

   Until then, emails appear in the backend log. At deployment the same variables, plus `FRONTEND_URL`, go into Render.
5. **Test:** post a recipe from a second, non-admin account, then approve it from the queue and check the email.

## 8. Out of scope

- A screen for managing admins.
- Email retries or a mail queue.
- Private storage for photos of pending recipes. Their storage URLs are random and unlisted, which is accepted for now.
- Review history beyond the latest decision.
- An in-app notification centre.
- Admins editing other users' recipes.
- Showing under-review recipes in browse, search or on public profiles.
