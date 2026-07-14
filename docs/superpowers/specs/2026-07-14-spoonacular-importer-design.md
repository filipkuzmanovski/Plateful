# Spoonacular Recipe Importer — Design

**Date:** 2026-07-14
**Scope:** A backend-only, property-gated import job that pulls recipes from the Spoonacular API, translates them into the existing `Recipe`/`RecipeIngredient` entities, dedups on `spoonacular_id`, and saves them to the Supabase Postgres DB. No new API endpoints, no frontend involvement, no schema changes.

## Context

The schema and domain model were built importer-ready: `recipes.source` has a `CHECK (source IN ('user','spoonacular'))`, `spoonacular_id BIGINT UNIQUE` is the dedup key, ownerless recipes (null `user_id`) are supported and editable by nobody, `instructions` (raw text) and `instructions_steps` (jsonb) coexist deliberately, and the `InstructionStep` record documents the exact flattening contract for Spoonacular's `analyzedInstructions`. The REST API (merged 2026-07-14) exposes recipes regardless of `source`, so imported rows appear in browse/filter endpoints immediately.

Decisions made during brainstorming:

- **Trigger:** CLI/property-gated `ApplicationRunner` inside the app — no admin endpoint (no role concept exists), no scheduler (free-tier quota, app runs from IntelliJ).
- **Content mix:** spread across cuisines via `complexSearch` (not `random`), so every cuisine filter in the UI returns results.
- **Volume:** ~400 recipes initial seed — 10 cuisines × 40 recipes, both configurable.
- **Architecture:** in-app runner + `RestClient` client + pure translator + import service (Approach A; standalone module and Spring Batch rejected as overkill).

## Components

All new code in package `mk.ukim.finki.wp.recipeappbackend.spoonacular`.

| Component | Responsibility |
|---|---|
| `SpoonacularProperties` | `@ConfigurationProperties(prefix = "spoonacular")`: `apiKey`, `baseUrl`, `import.enabled`, `import.cuisines` (List\<String\>), `import.recipesPerCuisine`, `import.offset` |
| `SpoonacularClient` | Thin `RestClient` wrapper. One method: `searchByCuisine(String cuisine, int number, int offset)` → `SpoonacularSearchResponse`. Sends `x-api-key` header. Maps HTTP 402 to `QuotaExceededException` |
| `SpoonacularSearchResponse` (+ nested records) | Only the JSON fields we consume: `results[]` with `id`, `title`, `image`, `servings`, `readyInMinutes`, `cookingMinutes`, `preparationMinutes`, `sourceName`, `sourceUrl`, `instructions`, `analyzedInstructions[]`, `vegan`, `vegetarian`, `glutenFree`, `dairyFree`, `cuisines[]`, `nutrition.nutrients[]`, `extendedIngredients[]`. Unknown properties ignored |
| `QuotaExceededException` | Thrown by the client on 402; signals "stop fetching, keep what we have" |
| `SpoonacularRecipeTranslator` | Pure function: API record → `Recipe` + `List<RecipeIngredient>`. No I/O. All mapping rules live here |
| `SpoonacularImportService` | Orchestrates one run: loop cuisines → fetch → per recipe: skip if `spoonacularId` exists, else translate + save. One transaction per recipe. Returns an `ImportSummary` (per-cuisine and total imported/skipped/failed) |
| `SpoonacularImportRunner` | `ApplicationRunner`, active only when `spoonacular.import.enabled=true`. Validates the API key is present (fail fast with a clear message if not), calls the service, logs the summary. The app continues serving normally afterward |

One addition to existing code: `RecipeRepository.existsBySpoonacularId(Long)` for the dedup check. Nothing else in the existing API layer changes.

**Data flow:** runner → service → (client → translator → `RecipeRepository`/`RecipeIngredientRepository`) per cuisine.

## Spoonacular API usage

One request per cuisine:

```
GET {baseUrl}/recipes/complexSearch
    ?cuisine={cuisine}
    &number={recipesPerCuisine}      (max 100)
    &offset={offset}
    &addRecipeInformation=true
    &addRecipeNutrition=true
    &fillIngredients=true
    &instructionsRequired=true
    &sort=popularity
```

- The single call returns full recipe info, nutrition, and ingredients — no per-recipe follow-up requests. This keeps a 400-recipe run to 10 HTTP requests and comfortably inside the free daily quota under normal conditions.
- `instructionsRequired=true` filters out content-less stubs; `sort=popularity` makes seed data presentable.
- API key goes in the `x-api-key` request header (not the URL, so it never lands in logs).
- Default cuisines: italian, mexican, chinese, indian, greek, french, japanese, thai, spanish, american.

## Translation rules

All implemented in `SpoonacularRecipeTranslator`, unit-tested against a captured JSON fixture:

- `source = "spoonacular"`, `user = null`, `spoonacularId` = API `id`.
- Straight copies: `title`, `image`, `servings`, `readyInMinutes`, `cookingMinutes`, `preparationMinutes`, `sourceName`, `sourceUrl`, raw `instructions` text (kept as fallback per the schema comment).
- **Instruction steps:** flatten all `analyzedInstructions` groups into one continuous `List<InstructionStep>`, renumbered 1..N across groups; map `ingredients[].name` / `equipment[].name` to plain name lists (drop images/temperatures) — exactly the contract in the `InstructionStep` javadoc. Empty/missing `analyzedInstructions` → empty list (raw text still present).
- **Diet flags:** direct from the API booleans (`vegan`, `vegetarian`, `glutenFree`, `dairyFree`); absent → false.
- **Cuisines:** lowercased from the API's `cuisines` array; if empty, fall back to `[searchedCuisine]` so every imported recipe remains filterable by the cuisine it was fetched under.
- **Nutrition:** from `nutrition.nutrients[]`, extract by name — "Calories" → `calories`, "Protein" → `proteinGrams`, "Fat" → `fatGrams`, "Carbohydrates" → `carbsGrams` (null when absent). Store the full nutrient list as a compact `Map<String, Object>` (`{name, amount, unit}` per nutrient) in the `nutrition` jsonb column.
- **Ingredients:** from `extendedIngredients[]` → `ingredientName` = `nameClean` (fallback `name`), `originalText` = `original` (fallback `originalName`, then `name`); `sortOrder` = list position; entries with no usable name AND no usable text are dropped.
- **Field-length safety:** `title` and `sourceName` truncated to 255 chars (their columns are VARCHAR(255); ingredient fields are TEXT and need no truncation). Bad API data must never abort an insert mid-run.

## Configuration & secrets

Appended to `application.properties`:

```properties
spoonacular.api-key=${SPOONACULAR_API_KEY:}
spoonacular.base-url=https://api.spoonacular.com
spoonacular.import.enabled=false
spoonacular.import.cuisines=italian,mexican,chinese,indian,greek,french,japanese,thai,spanish,american
spoonacular.import.recipes-per-cuisine=40
spoonacular.import.offset=0
```

- `SPOONACULAR_API_KEY` is set in the IntelliJ run configuration (like `SUPABASE_DB_PASSWORD`); the value never enters git. The default-empty placeholder (`:}`) means **the app boots normally without the key**; the runner validates the key only when `import.enabled=true` and fails fast with a clear message if it's missing.
- Triggering an import = adding program argument `--spoonacular.import.enabled=true` (optionally `--spoonacular.import.offset=N` to page deeper on later runs).

## Error handling & idempotency

- **Quota exhausted (HTTP 402):** `SpoonacularClient` throws `QuotaExceededException`; `SpoonacularImportService` stops fetching further cuisines, keeps everything already saved, and the summary reports progress plus a hint that re-running the same command after quota reset continues safely.
- **Idempotency:** dedup on `spoonacularId` before insert (`existsBySpoonacularId`); the DB's `UNIQUE` constraint is the backstop. Re-runs skip already-imported recipes. Insert-only — no refresh/upsert of previously imported recipes (YAGNI).
- **Per-recipe isolation:** each recipe (+ its ingredients) saves in its own transaction; a translation or save failure is logged with the `spoonacularId`, counted as `failed`, and the run continues.
- **Per-cuisine isolation:** network errors/5xx on one cuisine's request are logged, that cuisine is skipped, and the run continues with the next.
- **Summary:** one log block at the end — per-cuisine and total imported/skipped/failed.

## Testing

No live API calls in any test.

- `SpoonacularRecipeTranslatorTest` — the bulk of coverage, against a realistic captured JSON fixture (stored under `src/test/resources`): group flattening + continuous renumbering, name-only ingredient/equipment mapping, empty-cuisines fallback, nutrient extraction (present and absent), ingredient fallback chain, dropped empty ingredients, title truncation.
- `SpoonacularClientTest` — `MockRestServiceServer`-style stubbing: correct URL/query params, `x-api-key` header, deserialization of the fixture, 402 → `QuotaExceededException`.
- `SpoonacularImportServiceTest` — mocked client + repositories: dedup skip, quota-stop keeps earlier results, per-recipe failure isolation, summary counts.
- Runner behavior (disabled by default, fail-fast on missing key) covered by direct unit tests of the runner with mocked service.
- The existing 46 API tests are unaffected (new code is dormant without the flag).

## Out of scope

- Admin endpoint or scheduled/recurring imports.
- Refreshing/updating previously imported recipes.
- Importing images to own storage (image URLs are hotlinked from Spoonacular's CDN).
- Frontend work (next sub-project).
