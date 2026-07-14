# Plateful Frontend — Design

**Date:** 2026-07-14
**Scope:** A React SPA ("Plateful") consuming the finished backend REST API: browse/filter/detail, Supabase auth + profile provisioning, ratings, comments with reactions, recipe authoring, and user profiles. Lives in `recipe-app-frontend/` next to `RecipeAppBackend/` in the same repo.

## Context

The backend (merged 2026-07-14) exposes everything this app needs under `/api`: paginated recipe browse with filters (`cuisine`, `dairyFree`, `glutenFree`, `vegan`, `vegetarian`, `maxReadyInMinutes`, `minRating`), recipe detail with ingredients/steps/aggregates, ratings (upsert/get/delete "mine"), comments (list/add/edit/delete with author-or-recipe-owner moderation), comment reactions, and user profiles with the get-or-create provisioning handshake. Errors are RFC 9457 ProblemDetail (validation failures carry an `errors` field map); pages use the stable `{content, page:{size,number,totalElements,totalPages}}` shape; page size caps at 50. Auth is Supabase (JWT in `Authorization: Bearer`); CORS already allows `http://localhost:5173`. The DB is seeded with ~400 Spoonacular recipes across 10 cuisines.

Decisions made during brainstorming:

- **Stack:** React 19 + TypeScript on Vite (Approach A; Next.js and minimalist SPA rejected).
- **Libraries:** React Router, TanStack Query (all server state), supabase-js (auth ONLY — data always goes through our API), Tailwind CSS + shadcn/ui, react-hook-form + zod.
- **Scope:** everything the API offers (full v1).
- **Visual direction:** "Fresh Editorial" (chosen from mockups) — white space, near-black type, single green accent, square edges, photography-forward.
- **Browse layout:** filter bar on top, full-width grid (chosen from mockups).
- **Detail layout:** two-column — sticky photo/facts card left, scrolling content right (chosen from mockups).
- **Name:** Plateful.

## Structure & routing

```
recipe-app-frontend/
  src/
    api/          # typed API client: client.ts (fetch wrapper) + one module per resource
    auth/         # supabase client, AuthProvider, useAuth(), ProtectedRoute
    components/   # shared UI: RecipeCard, StarRating, FilterBar, Pagination, AppShell
    features/
      browse/     # BrowsePage (filters in URL search params)
      recipe/     # RecipeDetailPage, CommentsSection, RatingBox
      authoring/  # RecipeFormPage (create & edit share one form)
      profile/    # MyProfilePage, PublicProfilePage, CompleteProfilePage
      auth/       # LoginPage, SignupPage
    lib/          # queryClient, API DTO types
```

| Path | Page | Access |
|---|---|---|
| `/` | Browse | Public |
| `/recipes/:id` | Recipe detail | Public |
| `/recipes/new` | Create recipe | Auth |
| `/recipes/:id/edit` | Edit own recipe | Auth |
| `/users/:id` | Public profile + their recipes | Public |
| `/me` | My profile (edit names, my recipes) | Auth |
| `/login`, `/signup` | Supabase auth screens | Public |
| `/complete-profile` | First-login name collection | Auth |

Filter + page state live in the URL (`/?cuisine=italian&vegan=true&page=2`) — shareable, bookmarkable, back-button-friendly. `ProtectedRoute` redirects anonymous visitors to `/login` with a return-to location.

## Auth flow & API client

- **supabase-js is for auth only.** `AuthProvider` subscribes to session state and exposes `useAuth()` → `{ session, profile, signIn, signUp, signOut }`. Supabase URL + anon key come from `.env.local` (gitignored); a committed `.env.example` documents `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`, `VITE_API_BASE_URL`.
- **Provisioning handshake:** after login, call `GET /api/users/me`. 200 → cache profile in context. 404 → redirect to `/complete-profile`, collect first/last name, `POST /api/users/me` (idempotent). Authenticated actions stay hidden until the profile exists.
- **API client** (`src/api/client.ts`): typed fetch wrapper that prefixes `VITE_API_BASE_URL` (default `http://localhost:8080`), attaches the Supabase access token as `Authorization: Bearer` when a session exists, parses ProblemDetail into `ApiError { status, detail, errors? }`, and types the `{content, page}` shape as `Page<T>`. Resource modules (`recipes.ts`, `users.ts`, `ratings.ts`, `comments.ts`, `reactions.ts`) are the only places URLs appear.
- **TanStack Query:** keys `['recipes', filters, page]`, `['recipe', id]`, `['comments', recipeId, page]`, `['myRating', recipeId]`, `['userRecipes', userId, page]`, `['reactions', commentId, page]`. Mutations invalidate exactly the affected keys (rating → recipe detail + browse lists; comment → comments + recipe detail counts). A 401 API response signs the user out and redirects to `/login`.

## Screen behavior

**Browse (`/`):** top filter bar — cuisine dropdown (the 10 seeded cuisines), diet toggle chips, max-time dropdown (15/30/45/60), min-rating dropdown (3+/4+/4.5+); active filters shown as removable chips. Grid of 12 per page: photo, title, ★ average + count, ready-in time, cuisine tags. Page-number pagination from `page.totalPages`. Skeleton cards while loading; empty state with a "clear filters" action.

**Recipe detail (`/recipes/:id`):** two-column (stacks on mobile).
- Left sticky card: photo, ⏱ ready/cook/prep, servings, diet badges, nutrition macros (when present), and the RatingBox — read-only average for everyone; for logged-in users, clickable stars pre-filled from `GET .../ratings/me` (404 = unrated), PUT on click, DELETE when clicking the current value (un-rate). Optimistic update, invalidates detail aggregates.
- Right column: title + ★ average (count), source attribution ("From {sourceName} ↗" for imports, "by {firstName} {lastName}" linking to `/users/:id` for user recipes), ingredients as a checkable list (client-side only), numbered steps from `instructionsSteps` (fallback: raw `instructions` text; both absent is impossible for imports, but render text-only gracefully), then CommentsSection: paginated newest-first, post box (auth), 👍/👎 counts with optimistic PUT/DELETE reaction toggling, edit/delete on own comments, delete on any comment under your own recipe. Recipe owner additionally sees Edit / Delete (confirm dialog) for the recipe itself.

**Authoring (`/recipes/new`, `/recipes/:id/edit`):** one shared `RecipeFormPage`. Fields: title (required, ≤255), image URL (≤2048, live preview), servings/ready/cooking/prep minutes (non-negative ints), cuisines multi-select, four diet checkboxes, instructions textarea (≤50000), optional structured steps (add/remove step texts; submitted as `InstructionStep` objects with auto-assigned `number: 1..N` and empty `ingredients`/`equipment` arrays), dynamic ingredient rows (ingredientName ≤255 + originalText ≤500, required, add/remove/reorder; at least one row). zod schema mirrors backend caps; ProblemDetail `errors` map onto fields on submit failure. Create → 201 → navigate to the new detail page; edit pre-fills from the detail query; delete confirms then navigates home.

**Profiles:** `/users/:id` — display name + paginated grid of their recipes. `/me` — same plus name editing (`PUT /api/users/me`) and edit links on own recipes.

## Design system

Tailwind theme tokens for "Fresh Editorial":

- **Color:** near-black `#111111` text on white; single accent green `#16a34a` (ratings, active filter chips, primary buttons); gray hairlines `#e5e5e5`; muted text `#737373`. Photos carry the color; chrome stays monochrome.
- **Type:** Inter via Fontsource (self-hosted, no CDN). Extrabold tight-tracked wordmark "PLATEFUL"; uppercase letter-spaced micro-labels for section headings (INGREDIENTS, INSTRUCTIONS, COMMENTS); tabular numerals for times/counts.
- **Shape:** square corners, 2px black rules under major headings, 1px hairlines elsewhere. No drop shadows except a subtle hover lift on recipe cards.
- **Components:** shadcn/ui primitives (dialog, dropdown-menu, select, checkbox, input, textarea, toast) themed once to these tokens.
- **Responsive:** grid 4 → 2 → 1 columns; detail stacks left-card-first; filter bar collapses into a "Filters" sheet on mobile.

## Error handling

- ProblemDetail `detail` → toast for mutations, inline error panel with retry for page-load queries.
- Validation `errors` map → inline field errors in forms.
- 401 → global sign-out + redirect to `/login`.
- Route-level error boundary for render crashes; bad recipe id (404) → friendly "Recipe not found" page.
- Network failure → the query layer's retry (2 attempts) then the error panel.

## Testing

Vitest + React Testing Library + **MSW** (HTTP-level API fakes — tests run the real client → query → render pipeline). Focus areas:

- API client: base URL, auth header attach/omit, ProblemDetail → `ApiError`, `Page<T>` parsing.
- Provisioning: 404 from `/api/users/me` → redirect to `/complete-profile`; POST then proceeds.
- Browse: filter interactions ↔ URL search params ↔ request query params; pagination.
- RatingBox: anonymous read-only / authed pre-fill / rate / un-rate flows.
- Recipe form: zod validation, dynamic ingredient rows, server field-error mapping.
- Comments: post, moderation-dependent delete button visibility, reaction toggle.

Supabase auth is mocked at the client boundary. End-to-end against the live backend stays manual.

## Out of scope

- Favorites/bookmarks, meal planning, image upload (URLs only), search-by-text (API has no text search yet), admin tooling, dark mode, i18n.
- Deployment/hosting setup (dev runs on `vite dev` at `localhost:5173`).
