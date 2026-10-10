# Plateful "Golden Hour" Redesign — Design

**Date:** 2026-10-10
**Scope:** a visual redesign of the whole frontend in a new style, and a new filter drawer with a curated cuisine catalogue. This needs supporting backend changes (multi-cuisine/region filtering, a cuisines endpoint, server-side cuisine validation) and a rebalanced recipe dataset (an import replace mode). It builds on the merged frontend (`docs/superpowers/specs/2026-07-14-frontend-design.md`) and backend specs.

## Context and intent

The user finds the current "Fresh Editorial" look (black and white, square corners) bland, and the inline filter bar cramped. The site should look polished and be enjoyable to browse. Filters should be easy to explore without becoming a wall of options. The seeded data should be even (about 10 recipes per cuisine) and diverse, with users contributing the rest over time.

Current state (measured 2026-10-10):
- The database has 233 imported recipes. Tag counts: european 107, mediterranean 105, asian 84, indian/italian/mexican/chinese/french 40 each, greek 25, thai/spanish/american/japanese 2 each.
- `ready_in_minutes` ranges from 5 to 485.
- Recipe cuisines are stored as `text[]`. Filtering uses the `text_array_contains` SQL function from the cuisine-filter fix (commit 5679550).

Decisions made in brainstorming:

| Topic | Decision |
|---|---|
| Visual direction | **Golden Hour** (chosen from three mockups): warm cream, deep olive, saffron, terracotta; serif display type; soft rounded cards |
| Filter panel | **Slide-in drawer** from the right (bottom sheet on phones) with a live result count |
| Cuisine selection | **Multi-select**, results match ANY selected cuisine/region |
| Drawer cuisine content | Region chips, the 10 most popular cuisines, and an **A–Z view inside the drawer** listing every cuisine that has recipes |
| Cuisine vocabulary | **Curated, server-validated catalogue** of ≈200 country cuisines plus regional styles. No free-text tags, to prevent troll filters |
| Emojis | **None anywhere.** Use thin line icons (`lucide-react`) where an icon helps, plain text otherwise |
| Data | **Replace** all Spoonacular-imported recipes with ≈10 per cuisine across Spoonacular's 27 cuisines. User-created recipes are untouched. Explicit user confirmation before the run |
| Approach | Design-system restyle (tokens and shared components) plus targeted additions. No component-library switch, no rewrite |

## 1. Golden Hour design system

Tokens (Tailwind v4 `@theme` in `src/index.css`):

| Token | Value | Use |
|---|---|---|
| `cream` | `#FBF6EE` | page background |
| `paper` | `#FFFDF8` | cards, drawer, inputs |
| `olive` | `#2F3A2B` | primary text, primary buttons, header |
| `saffron` | `#E8A33D` | rating stars, focus rings, accents |
| `terracotta` | `#C65D3B` | italic headline flourishes, active dish-type pills, links |
| `sand` | `#EADFCD` | borders, hairlines |
| `muted` | `#8A8A7A` | secondary text |

- **Type:** Fraunces (variable serif) for the wordmark "Plateful." and headings, with selective *italic terracotta* words. Inter for body text and UI, with tabular numerals for times and counts. Both are self-hosted via Fontsource (no CDN).
- **Shape and depth:** 16–20px radius cards, pill-shaped buttons and chips, and soft olive-tinted shadows with a subtle hover lift. These replace the square corners and hard black rules.
- **Icons:** `lucide-react` line icons, replacing every emoji in the current UI:
  - 🍽 (missing photo) becomes a placeholder icon
  - ⏱ becomes `Clock`
  - servings get `Users`
  - 👍/👎 become `ThumbsUp`/`ThumbsDown`
  - the filter button gets `SlidersHorizontal`
  - **No emoji characters anywhere in the UI.**
- **Pages:**
  - **Header:** a cream bar with the serif wordmark.
  - **Browse:** a hero line ("Cook something *wonderful* tonight"), the Filters button with an active-count badge, and active-filter chips you can remove. Below them, a 4→2→1 grid of rounded photo cards.
  - **Recipe detail:** keeps the two-column layout, with a rounded sticky photo card, saffron stars, steps numbered in serif numerals, and soft comment bubbles.
  - **Authoring form and profiles:** the same tokens, with rounded inputs and section cards.
- **Accessibility:** text and controls meet WCAG AA contrast against cream and paper. Every existing accessible name and role is kept, so the existing tests keep their meaning.

## 2. Filter drawer

- **Trigger:** the "Filters" button (with `SlidersHorizontal` icon and active-count badge) in the browse hero.
- **Implementation:** a Radix Dialog styled as a right-side drawer, becoming a bottom sheet under 640px. It moves focus inside, closes on Escape, and dims the page behind it.
- **Main view, top to bottom:**
  1. **Regions:** Mediterranean, Europe, Asia, Americas, Africa, Middle East, Oceania, as pill chips. A region chip selects every catalogue cuisine in that region.
  2. **Popular cuisines:** the 10 cuisines with the most recipes (live from the API), as chips with counts.
  3. **"All cuisines A–Z (N) →"** opens the A–Z view.
  4. **Dish type:** Vegan, Vegetarian, Gluten free, Dairy free, as plain text pill toggles.
  5. **Time & rating:**
     - "Max time (minutes)" number input, 1–600, with quick picks 15 / 30 / 60.
     - "Min rating" number input, 1–5 in steps of 0.5, with a star preview.
     - Invalid input shows an inline hint and that field is excluded from the filter.
- **A–Z view** slides in inside the same drawer, so draft state is kept:
  - Every catalogue cuisine **that has at least one recipe**, grouped under letter headings, each with its count and a checkbox.
  - A search box and a clickable letter index.
  - "← Back" and "Done" return to the main view, where ticked cuisines are reflected.
- **Draft, then apply:** drawer changes are a local draft and touch nothing outside it.
  - A debounced live count query (the browse endpoint with `size=1`, reading `page.totalElements`) labels the primary button **"Show N recipes"**.
  - Pressing it writes the draft to the URL and closes the drawer.
  - **Clear all** resets the draft.
- **URL as source of truth:** `/?region=europe&cuisine=italian&cuisine=greek&vegan=true&maxReadyInMinutes=45&minRating=4`.
  - Active filters also render as removable chips above the grid.
  - Changing filters resets the page to 0.
  - Defensive parsing of numbers and the page number carries over.
- **Removed:** the current inline `FilterBar`, the hard-coded `CUISINES` list, and `cuisineLabel`. The drawer and the form read the catalogue from the API.

## 3. Backend

### 3.1 Cuisine catalogue (single source of truth)

- A `Cuisine` catalogue in the backend: an enum or an immutable registry class. Each entry has:
  - `key`: lowercase, the stored tag, e.g. `macedonian`, `middle eastern`
  - `label`: display text, e.g. "Macedonian", "Middle Eastern"
  - `region`: one of `europe`, `asia`, `americas`, `africa`, `middle_east`, `oceania`
  - `mediterranean`: boolean, true for cuisines around the Mediterranean plus the `mediterranean` style itself
- **Contents:**
  - a cuisine for every country, using the demonym label (Afghan … Zimbabwean), about 195 entries
  - every Spoonacular style tag: african, asian, european, latin american, middle eastern, mediterranean, eastern european, nordic, cajun, southern, caribbean, jewish, british, irish, american…
  - Every one of Spoonacular's 27 cuisine names must map to exactly one catalogue key.
- **Region membership:** a region selects all entries with that `region`. **Mediterranean** selects all entries with `mediterranean = true`.

### 3.2 Filtering

- `RecipeFilter` gains `List<String> cuisines` and `List<String> regions`, replacing the single `cuisine`. Spring binds the repeated params `cuisine` and `region`, and a single `?cuisine=x` keeps working.
- The service expands each region into its catalogue keys, unions them with the explicit cuisines, then normalizes (trim, lowercase) and dedupes.
- `RecipeSpecifications` ORs one `text_array_contains(cuisines, key)` predicate per key. That combined disjunction is ANDed with the other filters.
- Unknown cuisine or region values in a *filter* are ignored rather than rejected, so filtering by a value with no matches simply returns nothing.

### 3.3 `GET /api/recipes/cuisines` (public)

- Returns the full catalogue with live counts: `[{"key":"italian","label":"Italian","region":"europe","mediterranean":true,"count":12}, …]`, sorted by count descending, then label.
- Counts come from one native query: `SELECT c, count(*) FROM recipes, unnest(cuisines) c GROUP BY c`.
- The endpoint is public via the existing `GET /api/recipes/**` permitAll. The `cuisines` path segment must not be captured by `/api/recipes/{id}` (UUID type), so the route takes precedence or the id route is constrained. This must be verified in a controller slice test.
- Consumers:
  - the drawer's popular list (top 10 with count > 0)
  - the A–Z list (count > 0)
  - region membership (computed backend-side)
  - the form picker (all entries)

### 3.4 Validation on create and edit

- The recipe service normalizes each cuisine (trim, lowercase, collapse whitespace) and dedupes.
- **Any value not in the catalogue is rejected with HTTP 400** as a ProblemDetail field error on `cuisines`, so the frontend's existing field-error mapping shows it on the picker.
- Existing caps stay: at most 10 cuisines, each at most 50 characters.

### 3.5 Importer: replace mode and rebalance

- New property `spoonacular.import.replace-existing` (default `false`). When true, the runner first deletes all `source = 'spoonacular'` recipes in one transaction:
  - the database's `ON DELETE CASCADE` removes their ingredients, ratings and comments
  - `source = 'user'` recipes are never touched
  - the run logs how many recipes were deleted
- The import then proceeds as today, with dedup, quota stop, and per-recipe and per-cuisine isolation.
- The translator keeps only cuisine tags that exist in the catalogue, mapping Spoonacular names to catalogue keys. A recipe with no catalogue tags falls back to the searched cuisine (current behavior), which is always in the catalogue.
- Default config:
  - `spoonacular.import.cuisines` set to Spoonacular's 27 cuisines
  - `recipes-per-cuisine=10`
  - That's 27 requests, within the free daily quota.
  - Cross-cuisine dedup means a few cuisines may end below 10.
- **Running the replace import against the real database requires the user's explicit confirmation at that moment.**

## 4. Frontend components (new or changed)

| Unit | Responsibility |
|---|---|
| `src/index.css` | Golden Hour tokens and fonts |
| `components/ui.tsx` | Restyled atoms (Button variants, Field, TextInput, SectionHeading, Spinner) plus `Chip` and `Pill` |
| `components/AppShell.tsx`, `RecipeCard.tsx`, `StarDisplay.tsx`, `Pagination.tsx` | Restyled; emojis replaced by lucide icons |
| `api/cuisines.ts` | `getCuisines()` → `CuisineInfo[]` |
| `features/browse/FilterDrawer.tsx` | Drawer shell, draft state, live count, apply/clear |
| `features/browse/CuisineAZ.tsx` | A–Z view (letter groups, search, index) |
| `features/browse/filterParams.ts` | URL ↔ filter parsing and serialization (multi `cuisine`/`region`, defensive numbers); extracted from BrowsePage |
| `features/browse/ActiveFilterChips.tsx` | Removable chips above the grid |
| `features/browse/BrowsePage.tsx` | Hero, Filters button, chips, grid (FilterBar removed) |
| `features/authoring/CuisinePicker.tsx` | Searchable multi-select over the catalogue (max 10) |
| Recipe detail, comments, form, profile pages | Restyled to tokens; behavior unchanged |

## 5. Error handling

- **Cuisines endpoint fails:** the drawer still opens, with dish type and time/rating usable. The cuisine area shows "Couldn't load cuisines" with a retry button. The form picker shows the same message and blocks only the cuisine field.
- **Live count fails or is slow:** the button reads "Show recipes" and still applies.
- **Invalid typed time or rating:** an inline hint, and the field is excluded. Never blocks applying.
- **Unknown cuisine on save:** the 400 field error is shown on the picker.
- **Replace import fails after the delete:** fewer recipes but a consistent database. Re-running completes it (dedup makes re-runs safe).

## 6. Testing

- **Backend unit and slice tests, each written to fail first:**
  - multi-cuisine OR predicate, plus single-param backward compatibility
  - region expansion, including Mediterranean
  - unknown-cuisine 400 on create and edit
  - normalization and dedup
  - cuisines endpoint: public, shape, sort order, `cuisines` not captured by `{id}`
  - importer replace mode: deletes only Spoonacular recipes, then imports
  - translator catalogue filtering and name mapping
  - All 68 existing backend tests stay green.
- **New: opt-in real-Postgres repository tests** for the cuisine queries: the any-of filter SQL and the counts query.
  - They're annotated `@Tag("db")` and enabled only when `SUPABASE_DB_PASSWORD` is set (`@EnabledIfEnvironmentVariable`).
  - Read-only, so the normal no-database run is unaffected.
  - Motivation: the cuisine-filter 500 showed that MSW and mocked repositories hide SQL errors.
- **Frontend (MSW):**
  - the drawer opens and closes (keyboard and Escape)
  - draft edits don't change the URL until Apply
  - the live count label
  - a region chip sends `region=`
  - the A–Z view: search, ticks preserved across views, only count > 0 listed
  - typed-input validation
  - removable active chips
  - the form picker: search, max 10, 400 field error shown
  - Existing tests are updated only where markup intentionally changed (the FilterBar removal, emoji replacement) and are never weakened.
- **Visual acceptance:** the user reviews the restyled pages in the browser before merge.

## 7. Rollout order

Each step is its own commits, pushed as it lands:
1. Backend: catalogue, validation, multi-cuisine/region filter, cuisines endpoint, real-DB tests.
2. Backend: import replace mode and catalogue-aware translator.
3. Frontend: Golden Hour design system and restyle of all pages (no emojis).
4. Frontend: filter drawer, A–Z view, active chips, URL params.
5. Frontend: recipe-form cuisine picker.
6. Data: replace-import run, **after explicit user confirmation**.
7. Final whole-branch review, merge to `main`, push.

The dev log (`docs/plateful-frontend-dev-log.txt`) gets a section per step.

## Out of scope

- User-invented cuisine tags (rejected for troll risk; the curated catalogue covers every country).
- Dark mode, i18n, favorites, text search.
- Changing the Spoonacular daily-quota handling beyond the replace mode.
