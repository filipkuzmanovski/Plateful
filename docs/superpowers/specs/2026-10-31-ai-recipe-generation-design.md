# AI recipe generation and daily meals: design

Date: 2026-10-31
Status: approved in conversation section by section; this written spec is awaiting review.
Sub-project 2 of the AI features (see `docs/ai-features-handoff.md`). Builds on sub-project 1
(`2026-10-30-food-preferences-and-dataset-design.md`, merged at `8fba37f`).

## 1. Goal

1. **An AI provider behind a small Java interface.** Two free providers, Groq and Google Gemini, tried in
   order. The keys live only in backend environment variables.
2. **Feature 1, "Cook with what I have".** A signed-in user enters the ingredients they have and gets a
   **new, original** recipe. The AI is grounded in the RecipeNLG reference data, and code checks the
   result for safety (allergies, diets, dislikes) and novelty.
3. **Feature 3, today's meals.** Breakfast, lunch and dinner are generated once per user per local day
   from their saved preferences, stored, and served on every later visit.

AI recipes are **private to the user who asked for them**. They are never published, never shown
publicly and never enter the admin review.

## 2. Decisions from the conversation

| Question | Decision |
|---|---|
| Providers | Groq and Gemini behind one `RecipeAi` interface, tried in the order `plateful.ai.order`. **Default `groq,gemini`**: the user's AI Studio page shows Gemini 3.8 Flash at 5 RPM, 250K TPM and **20 requests per day**, while Groq gives 1,000 per day (Zero Data Retention is on). Gemini catches Groq's 8K-tokens-per-minute bursts. Either key alone also works. |
| Publishing AI recipes | **Never.** AI recipes are only for the requesting user. This replaces the handoff's "Publish goes to admin review". |
| Where they live | A new `generated_recipes` table only, never `recipes`. **Save** sets `saved_at`. Saved ones appear in a new profile tab. `recipes`, browse, search, the admin queue and `RecipeVisibility` are untouched, so there is no `source = 'ai'` value and no new recipe status. |
| Per-user limit | **10** feature-1 generations per user per UTC day, shown as "N left today". A generation counts once, however many AI calls its retries take. Daily meals do not count. |
| Feature 1 inputs | 2 to 15 ingredients; an optional meal type (Any, Breakfast, Lunch, Dinner); an **Only use what I have** switch. Off (default): pantry staples plus at most 3 extras, shown as "You'll also need". On: only the user's ingredients plus staples. |
| Daily meals UI | A "Today's meals" strip at the top of the home page for signed-in users. Generated on the first visit of the day (one AI call for all three), with placeholder cards while waiting. |
| Quota fallback | The user's most recent ready set, labelled with its date; "not available yet" when there is none. No database picker. |
| Build approach | Plain `RestClient` calls, run synchronously inside the request; no Spring AI, no job queue. |
| Training | **No model is trained.** RecipeNLG is used at request time as retrieved examples and pairing facts in the prompt (retrieval-augmented generation). |

## 3. Architecture

### 3.1 Package `ai`: provider transport only

Providers know nothing about recipes, so adding one is one class.

- **`RecipeAi`**: `AiReply complete(AiRequest request)`.
  - `AiRequest(String systemText, String userText, Map<String, Object> jsonSchema, double temperature, int maxOutputTokens)`.
  - `AiReply(String json, String provider, String model, Integer inputTokens, Integer outputTokens)`.
- **`GroqRecipeAi`**: `POST https://api.groq.com/openai/v1/chat/completions`, header `Authorization: Bearer <key>`,
  `response_format` of type `json_schema` with the schema. If the configured model turns out not to
  support `json_schema`, the plan falls back to `json_object` with the schema written into the system
  text; the parser validates either way.
- **`GeminiRecipeAi`**: `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`,
  header `x-goog-api-key: <key>` (never a URL parameter), `systemInstruction`, `generationConfig` with
  `temperature`, `maxOutputTokens`, `responseMimeType: application/json`, the JSON schema, and the lowest
  supported thinking level (`low`). The plan confirms the exact field names against the current docs.
- **Errors:** HTTP 429, or a body saying the quota or rate limit is exhausted, throws
  `AiQuotaExceededException`. A timeout, connection failure, any 5xx, or an unreadable reply throws
  `AiUnavailableException`. Messages never contain the key or the request body.
- **Timeouts:** 5 s to connect, 30 s to read, per call.
- **`FallbackRecipeAi`** (the `@Primary` `RecipeAi` bean): tries the active providers in
  `plateful.ai.order`.
  - A provider is active when its key property is non-blank.
  - After a quota answer the provider is skipped for the provider's `Retry-After` (seconds, clamped to 1 to
    300), or **60 seconds** when the header is missing or unparsable (in memory, using an injected `Clock`).
    HTTP 413 is "unavailable": the next provider is tried and there is no cooldown.
  - Every provider quota-limited or cooling down → `AiQuotaExceededException`. Any other mix of failures
    → `AiUnavailableException`. No active provider → `AiNotConfiguredException`.
- **Logging:** one INFO line per call: provider, model, outcome, milliseconds, token counts. Never the
  prompt, the reply or the key.

### 3.2 Package `generation`: recipe logic

- **`ReferenceFinder`**:
  - `inspiration(...)`: `ReferenceDataRepository.findClosest` with the user's filters, limit 20; every
    candidate re-checked in Java with `FoodRules.allergensIn`, `FoodRules.dietViolations` and the dislike
    rule on its `ingredient_names` (sub-project 1 spec §11); the first 6 (feature 1) or 2 per meal
    (daily) kept.
  - `pairings(names)`: for up to 5 of the given names, the top 8 partners from `ingredient_pairings`
    with `together_count / recipe_count` as a percentage, skipping partners the user may not eat.
  - `comparisonSet(draftNames)`: what the novelty check compares against (§5.4).
- **`PantryStaples`**: the fixed list in `src/main/resources/pantry-staples.txt`, one normalised name per
  line: salt, black pepper, pepper, water, oil, olive oil, vegetable oil, sugar, brown sugar, flour,
  butter, vinegar, baking powder, baking soda, cornstarch.
- **`PromptBuilder`**: system text, user text and JSON schema for both flows (§5.2, §6.3).
- **`DraftParser`**: JSON text to `RecipeDraft`, with the structure check (§5.3).
- **`SafetyCheck`**: allergens, diets, dislikes, staples and extras (§5.3).
- **`NoveltyCheck`**: ingredient and title similarity (§5.4).
- **`RecipeGenerationService`**: feature 1 (§5).
- **`DailyMealsService`**: today's meals (§6).
- **`AiUsageService`**: the 10-a-day counter (§7).
- **`GeneratedRecipeCleanup`**: the daily cleanup job (§4.3). `@EnableScheduling` is added next to the
  existing `@EnableAsync`.

### 3.3 Changes to existing code

- **`FoodRules`** gains `allergensInText(String text)` and `dietViolationsInText(String text, Set<String> diets)`:
  the same deletion-masking matcher, run on free text (a step) that is lowercased, hyphens turned into
  spaces, everything but letters and spaces removed, spaces collapsed, and padded, with no 60-character
  limit. Every word is singularised with `IngredientNames`' last-word rule, so "peanuts" in a step hits
  "peanut".
- **`ReferenceDataRepository`** gains:
  - `randomForMeal(mealType, allergens, diets, dislikes, limit)`: random reference recipes for a meal with
    the same hard filters as `findClosest`, picked with `ORDER BY random()` (about 15,000 rows per meal,
    so a full scan is fast enough);
  - `pairings(ingredient, limit)`: from `ingredient_pairings` joined with `ingredient_stats`.
- **A Plateful candidate query** (in `RecipeIngredientRepository` or a small JDBC repository): approved
  recipes whose ingredient names contain any of the given words, up to 50, returning recipe id, title
  and ingredient names. Names are normalised in Java.
- **`GlobalExceptionHandler`**: handlers for the new exceptions, each adding a `code` property (§7).
- **`SecurityConfig`**: no change; every new path is under `/api/users/me/...`, which already needs
  authentication and passes through `MfaEnforcementFilter`.

## 4. Data model: Flyway `V9__ai_generation.sql`

```sql
CREATE TABLE generated_recipes (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind           TEXT NOT NULL CHECK (kind IN ('cook', 'daily')),
    meal_type      TEXT CHECK (meal_type IN ('breakfast', 'lunch', 'dinner')),
    title          VARCHAR(255) NOT NULL,
    recipe         JSONB NOT NULL,
    request        JSONB NOT NULL,
    provider       TEXT NOT NULL,
    model          TEXT NOT NULL,
    novelty_score  NUMERIC(4,3) NOT NULL,
    reference_ids  BIGINT[] NOT NULL DEFAULT '{}',
    saved_at       TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_generated_recipes_saved ON generated_recipes (user_id, saved_at DESC) WHERE saved_at IS NOT NULL;
CREATE INDEX idx_generated_recipes_created ON generated_recipes (created_at);

CREATE TABLE daily_meal_sets (
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    meal_date    DATE NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('generating', 'ready', 'failed')),
    breakfast_id UUID REFERENCES generated_recipes(id),
    lunch_id     UUID REFERENCES generated_recipes(id),
    dinner_id    UUID REFERENCES generated_recipes(id),
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, meal_date)
);

CREATE TABLE ai_usage (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    usage_date  DATE NOT NULL,
    generations INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, usage_date)
);

ALTER TABLE generated_recipes ENABLE ROW LEVEL SECURITY;
ALTER TABLE daily_meal_sets   ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_usage          ENABLE ROW LEVEL SECURITY;
```

No public policies; the backend connects as the table owner, as for every table.

### 4.1 Column meanings

- **`recipe`**: the checked `RecipeDraft` plus what the code computed:
  `{title, description, servings, prepMinutes, cookMinutes, cuisine, ingredients: [{name, amount, extra}],
  steps: [..], diets: {vegetarian, vegan, glutenFree, dairyFree}, allergens: [keys]}`.
- **`request`**: what was asked. Feature 1: `{ingredients, mealType, onlyWhatIHave}`. Daily:
  `{mealDate}`.
- **`novelty_score`**: the highest ingredient Jaccard against any compared recipe (§5.4).
- **`reference_ids`**: the RecipeNLG ids sent as examples.
- **`saved_at`**: null means not saved.
- **`daily_meal_sets.meal_date`**: the user's local date as sent by the browser. **`ai_usage.usage_date`**:
  the UTC date.

### 4.2 Concurrency

No database transaction or connection is held during an AI call (Supabase allows about 15 sessions).

- **Daily set claim:** `INSERT ... (status 'generating') ON CONFLICT DO NOTHING`. If no row was inserted,
  a conditional `UPDATE ... SET status = 'generating', attempted_at = now() WHERE ... AND (status =
  'generating' AND attempted_at < now() - interval '2 minutes' OR status = 'failed' AND attempted_at <
  now() - interval '15 minutes')` takes over a crashed or cooled-down attempt. Whoever changed a row
  generates; everyone else reads the row as it is.
- **Usage reserve:** one statement, `INSERT ... VALUES (:user, :day, 1) ON CONFLICT (user_id, usage_date)
  DO UPDATE SET generations = ai_usage.generations + 1 WHERE ai_usage.generations < :limit RETURNING
  generations`. No row returned means the limit is reached. A refund is `generations - 1` with a floor of 0.

### 4.3 Cleanup

`GeneratedRecipeCleanup` runs daily at 03:00 UTC (`@Scheduled(cron = "0 0 3 * * *", zone = "UTC")`):

1. delete `daily_meal_sets` rows with `meal_date` older than 30 days, except each user's latest `ready` row;
2. delete `generated_recipes` older than 30 days with `saved_at IS NULL` that no remaining set references.

Both run in one short transaction. Account deletion cascades through `users`.

## 5. Feature 1: ingredients to a new recipe

`POST /api/users/me/ai-recipes/generate`, body `{ingredients: string[], mealType: string | null,
onlyWhatIHave: boolean}`; response 200 with `{recipe: GeneratedRecipeDto, remainingToday: number}`.

### 5.1 Steps

1. **Preferences.** No `user_preferences` row → 409 `no_preferences`.
2. **Check the request.** Ingredients are normalised with `IngredientNames`; names that normalise to
   nothing are dropped, and duplicates removed; 2 to 15 must remain, else 400. `mealType` is null or a meal key, else 400.
   Any ingredient that breaks the user's allergens, diets or dislikes → 400 `invalid_request` with a
   `conflicts` property listing `{ingredient, reason}` (for example `{"peanut", "allergy: Peanuts"}`), and
   the detail "Some ingredients conflict with your food preferences."
3. **Reserve** one generation (§4.2). None left → 429 `daily_limit`.
4. **Gather inspiration** (`ReferenceFinder`): 6 examples and the pairing facts. Zero examples (for
   example before the dataset is loaded) is allowed: the prompt then has no examples section.
5. **Up to 3 attempts**, each: prompt (§5.2) → AI call → parse and structure check → safety check →
   novelty check. A failure in parsing or a check adds a line to the next attempt's prompt saying what
   went wrong. No attempt starts if less than 30 s remain of a 75 s budget from step 3.
6. **Pass:** insert a `generated_recipes` row (`kind = 'cook'`) and return it.
7. **Failure:** refund the reservation. All attempts failed checks → 422 `generation_failed`. Quota →
   503 `ai_quota`. Unavailable → 503 `ai_unavailable`. Not configured → 503 `ai_not_configured`.

### 5.2 Prompt

- **System text:** the model is a creative cook who invents **new** dishes: its own name, its own
  combination, its own method. The examples are inspiration only and must never be copied or lightly
  renamed. Quantities must be realistic for the servings. Reply with JSON matching the schema, nothing
  else. English only.
- **User text:**
  - the user's ingredients and the mode (only these plus staples, or staples plus at most 3 extras);
  - the staples list;
  - the meal type, if any;
  - **"Never use"**: the user's disliked ingredients plus the plain-word terms of their allergens and
    diets from `FoodRules` (the terms only, never the words "allergy" or "diet", so the provider learns
    nothing about health);
  - the examples: title, ingredient names and up to 6 shortened steps each;
  - the pairing facts ("tomato often goes with: basil 31%, garlic 28%, ...");
  - a cooking technique picked by the code from a fixed list (roast, bake, braise, stew, stir-fry,
    pan-fry, grill, steam, simmer, raw/no-cook), with an injected `Random`;
  - on a retry, the reason the last attempt failed.
- **Temperature** 1.0; **max output tokens** 2,048.
- **Never sent:** the user's name, email, id, or anything from their profile other than the above.

### 5.3 Structure and safety checks

**Structure** (`DraftParser`): valid JSON with title (1 to 120 characters), description (up to 300),
servings 1 to 12, prep and cook minutes 0 to 600, cuisine null or a key `CuisineCatalog` knows (an
unknown key becomes null rather than failing), 3 to 20 ingredients each with name (1 to 60) and amount
(up to 120), 2 to 15 steps each up to 600 characters. Ingredient names must normalise to something.

**Safety** (`SafetyCheck`, always with the current `food-rules.csv`):
- no ingredient name hits one of the user's allergens (`allergensIn`), breaks a diet (`dietViolations`)
  or contains a disliked name as a whole word;
- no step text does either (`allergensInText`, `dietViolationsInText`, the dislike rule on the cleaned
  text);
- **coverage:** an ingredient is covered when it and one of the user's ingredients or a staple contain
  each other as whole words ("cherry tomato" is covered by "tomato"; "extra virgin olive oil" by
  "olive oil"). Uncovered ingredients are **extras**: none allowed when `onlyWhatIHave`, at most 3
  otherwise. Extras are marked `extra: true`.
- Staples are not exempt from the allergy, diet and dislike checks: "flour" still breaks gluten-free.

The stored `diets` and `allergens` are computed from the ingredient names by `FoodRules`, never taken
from the AI.

### 5.4 Novelty check

**Compared set** (`ReferenceFinder.comparisonSet`): the 6 examples, plus `findClosest(draft names, no
filters, limit 10)`, plus the 20 approved Plateful recipes with the most ingredient names in common with
the draft.

**Ingredient similarity:** Jaccard over sets of normalised ingredient names, **with staples removed from
both sets**. If either set is empty after that, the ingredient test is skipped for that pair.

**Title similarity:** Jaccard over sets of lowercase title words, after removing: a, an, and, the,
with, of, in, on, for, style, easy, quick, simple, best, homemade, recipe, my.

**Too close** when, for any compared recipe, ingredient similarity **≥ 0.60** or title similarity
**≥ 0.75**. The thresholds are constants in `NoveltyCheck`, pinned by tests at both edges (0.59 passes,
0.60 fails; 0.74 passes, 0.75 fails). The retry line names the closest title.

## 6. Feature 3: today's meals

`GET /api/users/me/daily-meals?date=YYYY-MM-DD`.

### 6.1 Responses

| State | Body |
|---|---|
| Ready for that date | `{status: "ready", date, fresh: true, meals: {breakfast, lunch, dinner}}` |
| Being generated by another request | `{status: "generating"}` |
| Fallback | `{status: "ready", date: <older date>, fresh: false, meals}` |
| Nothing to show | `{status: "unavailable"}` |

`meals` holds `GeneratedRecipeSummaryDto`s (id, title, description, total minutes, meal type).
The date must be a valid ISO date within one day of the server's UTC date, else 400. No preferences →
409 `no_preferences`.

### 6.2 Steps

1. A `ready` row for the date → return it, after the re-check below.
2. Try to claim the row (§4.2). Not claimed and the row is `generating` → `{status: "generating"}`. Not
   claimed and `failed` (still cooling down) → the fallback.
3. Inspiration per meal: `findClosest(liked ingredients, meal, filters)` with the Java re-check, 2 kept;
   when liked ingredients are empty or fewer than 2 come back, `randomForMeal` tops up to 2 (6 examples in
   total, to stay inside Groq's tokens per minute).
4. One AI call (§6.3).
5. Each meal is parsed and checked on its own: structure, safety (no coverage rule, no extras limit) and
   novelty.
6. If any meal failed, **one** more call asks only for the failed meals, with the reasons.
7. All three pass → insert three rows (`kind = 'daily'`, `meal_type` set) and mark the set `ready`, in
   one short transaction. Otherwise mark it `failed` and return the fallback.
8. AI errors (quota, unavailable, not configured) also mark the set `failed` and return the fallback.

The fallback is the user's most recent `ready` set with an earlier date, or `unavailable`.

Stored sets are re-checked against the current preferences before they are served: every stored recipe of
the set (today's or the fallback) is rebuilt as a draft and run through the safety check (`Mode.ANY`) with
the preferences as they are now. An unsafe set for today → the fallback instead (no new generation today);
an unsafe fallback → `unavailable`. No preferences row at that point counts as unsafe. An unknown profile
key still fails closed (the exception goes on).

Daily meals never use the 10-a-day counter. A preference change made during the day does not start a new
generation; a fresh set that fits it is made the next day.

### 6.3 Prompt

The same system text as §5.2, asking for a JSON object `{breakfast, lunch, dinner}`, each a recipe
matching the feature-1 schema. The user text has: liked cuisines and liked ingredients as "lean
towards"; the same "Never use" list; the 6 examples (2 per meal), labelled by meal; the titles of the user's daily
meals from the last 7 days as "do not repeat these"; a random technique per meal. Temperature 1.0, max
output tokens 4,096. About 4,500 tokens in total, inside Groq's 8K tokens per minute.

## 7. Other endpoints, limits and errors

### 7.1 Endpoints

All under `/api/users/me/`, all signed-in only:

| Method and path | Result |
|---|---|
| `POST ai-recipes/generate` | §5 |
| `GET ai-recipes?page=&size=` | the user's **saved** AI recipes, newest saved first, as summaries (page size up to 50) |
| `GET ai-recipes/{id}` | one of the user's AI recipes, saved or not; another user's id → 404 |
| `PUT ai-recipes/{id}/saved` | sets `saved_at` if null; 204 |
| `DELETE ai-recipes/{id}/saved` | clears `saved_at`; 204 |
| `GET ai-recipes/usage` | `{used, limit, remaining}` for today (UTC) |
| `GET daily-meals?date=` | §6 |

`GeneratedRecipeDto`: id, kind, mealType, title, description, servings, prepMinutes, cookMinutes,
cuisine, ingredients `[{name, amount, extra}]`, steps, diets, allergens `[{key, label}]`, saved,
createdAt, provider.

### 7.2 Errors

All are `ProblemDetail`s with a `code` property:

| Situation | HTTP | `code` | Detail |
|---|---|---|---|
| Bad count, bad meal type, or conflicts (with `conflicts`) | 400 | `invalid_request` | names the problem |
| No preferences saved | 409 | `no_preferences` | "Set your food preferences first." |
| 10 used today | 429 | `daily_limit` | "You've used today's 10 AI recipes. More tomorrow." |
| All providers quota-limited | 503 | `ai_quota` | "The free AI limit is reached for now. Try again later." |
| Providers failing otherwise | 503 | `ai_unavailable` | "The AI service isn't responding. Try again in a minute." |
| No key configured | 503 | `ai_not_configured` | "AI features aren't set up on this server." |
| 3 attempts failed the checks | 422 | `generation_failed` | "Couldn't make a safe new recipe from these. Try changing the ingredients." |

### 7.3 Configuration

```
plateful.ai.groq.api-key=${GROQ_API_KEY:}
plateful.ai.groq.model=openai/gpt-oss-120b
plateful.ai.gemini.api-key=${GEMINI_API_KEY:}
plateful.ai.gemini.model=gemini-3.8-flash
plateful.ai.order=groq,gemini
plateful.ai.daily-limit=10
```

## 8. Frontend

- **API and types:** `api/ai.ts` (`generateRecipe`, `getAiRecipe`, `listSavedAiRecipes`, `saveAiRecipe`,
  `unsaveAiRecipe`, `getAiUsage`, `getDailyMeals`); types in `lib/types.ts`. An API error helper exposes
  the `code` property.
- **Header:** a **Cook** link for signed-in users, to `/cook`.
- **`features/ai/CookPage.tsx` at `/cook`** (in `ProtectedRoute`), titled "Cook with what I have":
  - the existing `IngredientChipInput` (2 to 15); chips named in a 400 `conflicts` list are highlighted
    with the reason;
  - a meal-type choice (Any, Breakfast, Lunch, Dinner), an **Only use what I have** switch, and a
    **Generate** button with "N left today" under it;
  - while waiting: the button is disabled, "Creating your recipe. This can take up to a minute.", and a
    placeholder card;
  - the result below the form in `AiRecipeView`, with **Generate another** (same inputs), **Save**
    (then **Saved**, which unsaves on click) and **Open** (to `/ai-recipes/:id`);
  - each error `code` shows its own message; `daily_limit` also disables Generate.
- **`features/ai/AiRecipeView.tsx`**, used wherever an AI recipe is shown:
  - a highlighted note: "Generated by AI. Not checked by a person. Use your judgement, especially with
    meat, eggs and allergens.";
  - a plain styled header (Golden Hour tokens, a lucide icon) in place of a photo;
  - title, description, servings, prep and cook time, cuisine label;
  - "Contains: Milk, Eggs" (or "No listed allergens found"), diet badges;
  - ingredients, then a "You'll also need" group for extras;
  - numbered steps; a small "Made with Groq" or "Made with Gemini" line.
  - No ratings, comments or share.
- **`features/ai/AiRecipePage.tsx` at `/ai-recipes/:id`** (protected): `AiRecipeView` plus Save or
  Remove. 404 → the existing "Page not found" empty state.
- **`features/ai/TodaysMeals.tsx`** on the home page above the browse content, for `status === 'ready'`
  users only:
  - three cards labelled Breakfast, Lunch, Dinner, linking to `/ai-recipes/:id`;
  - `generating` (or the first request in flight): three placeholder cards and "Making today's
    meals..."; polls every 3 s for up to 2 minutes;
  - fallback: "From 28 October. Today's meals couldn't be made fresh." (the date formatted in English);
  - unavailable or an error: "Today's meals aren't available yet. Try again later.";
  - the date sent is the browser's local date (`YYYY-MM-DD`).
- **Profile:** a fourth tab, **AI recipes** (`?tab=ai`), listing saved ones (title, meal type or "Cook",
  saved date) with Remove; keyboard navigation covers four tabs.
- **Privacy page** (`PrivacyPage.tsx`), a new paragraph: AI recipes are made by Groq, falling back to
  Google Gemini. Sent: the ingredients typed, the meal type, a list of ingredients to avoid, liked
  cuisines and ingredients, and example recipes from a research dataset. Never sent: name, email or
  account. Gemini's free tier may use what is sent to improve Google's products, and human reviewers may
  read it; Groq's data retention is switched off. Don't type personal information into the ingredient
  box. AI recipes are stored privately on the account and deleted with it; unsaved ones are deleted after
  30 days.
- **No emojis;** every new screen's tests call `expectNoEmoji()`.

## 9. Testing

No automated test makes a real AI call.

### 9.1 Backend unit and slice tests

- **`GroqRecipeAiTest`, `GeminiRecipeAiTest`** (`MockRestServiceServer`): request shape (key header, never
  the URL; schema; temperature; tokens), reply parsing, 429 → quota, 5xx and timeout → unavailable, key
  text absent from every exception message.
- **`FallbackRecipeAiTest`:** order; 60 s cooldown with a fake `Clock`; all quota → quota; mixed →
  unavailable; none active → not configured.
- **`PromptBuilderTest`:** ingredients, mode, staples, "Never use" terms present; the words "allergy" and
  "diet" absent; a test user's name, email and id absent; technique from the list with a seeded `Random`;
  retry reason included.
- **`DraftParserTest`:** valid draft; every bound at its edge; malformed JSON; unknown cuisine → null.
- **`SafetyCheckTest`:** allergen in a name; allergen only in a step; diet violation; dislike; coverage
  rules; only-what-I-have; 4 extras rejected and 3 allowed; flour still breaks gluten-free.
- **`NoveltyCheckTest`:** both thresholds at both edges; staples removed before Jaccard; empty sets skip
  the ingredient test; stop words removed from titles.
- **`FoodRulesTest`:** the free-text matcher (plurals, hyphens, exceptions still masking).
- **`RecipeGenerationServiceTest`** (fake `RecipeAi`): pass first time; retry carries the reason; 3
  failures → 422 and refund; quota → 503 and refund; conflicts → 400 with no AI call and no reservation;
  no preferences → 409; zero examples still works.
- **`DailyMealsServiceTest`:** ready served; generating returned; stale claim taken over; failed
  cooldown; fallback to the latest earlier set; unavailable; second call only for failed meals; date
  validation.
- **`AiRecipeControllerTest`, `DailyMealsControllerTest`:** every endpoint and error `code`; 404 for
  another user's recipe; 2FA guarding with the existing filter-test pattern.
- **`GeneratedRecipeCleanupTest`:** the job calls the cleanup in order (repository mocked).

### 9.2 DbTests

Exactly `RecipeCuisineQueryDbTest`'s annotations, **no `@Import`**, writes only inside the rolled-back
transaction:
- the usage reserve stops at the limit and the refund floors at 0;
- a second claim of the same daily set changes nothing; stale and cooled-down takeovers work;
- `randomForMeal` respects every filter (skipped by assumption until the dataset is loaded);
- `pairings` order and limit (same assumption);
- the cleanup keeps each user's latest ready set and every saved recipe;
- the Plateful candidate query returns approved recipes only.

### 9.3 Frontend (Vitest, React Testing Library, MSW)

- `/cook`: payload; "N left today"; waiting state; a message per error `code`; conflict chips
  highlighted; Generate another resends the same inputs; Save and unsave.
- `AiRecipeView`: the AI note text, Contains line, extras group, provider line.
- Today's meals: ready; generating → polling → ready; fallback with its date; unavailable; not rendered
  for anonymous users.
- Profile AI tab: list, Remove, keyboard navigation across four tabs.
- `/ai-recipes/:id` 404; the Privacy paragraph.
- `expectNoEmoji()` on every new screen.

### 9.4 Manual check by the user (real keys)

1. Generate twice with the same ingredients: two different recipes.
2. Switch on "Only use what I have": no extras.
3. Try an allergen-heavy list: the conflicting chips are named.
4. Load the home page: today's meals appear; reload: they load instantly.
5. Save a recipe, find it in the profile tab, remove it.

## 10. Manual steps for the user

Done on 2026-10-31 (confirmed in conversation; the run configuration was checked without printing
values):

1. **Gemini key:** aistudio.google.com → **Get API key** → **Create API key** in a new or default
   project; no billing account attached, so it stays free.
2. **Groq key:** console.groq.com → **API Keys** → **Create API Key** (`plateful-dev`); copied once.
3. **Groq Zero Data Retention:** **Settings → Data Controls → Global ZDR** switched on.
4. **IntelliJ:** **Run → Edit Configurations... → RecipeAppBackendApplication → Environment variables**:
   `GEMINI_API_KEY` and `GROQ_API_KEY` added.

Still to do:
- **Load the RecipeNLG data** (sub-project 1, `tools/dataset/README.md`) if not done yet. Generation works
  without it but has no examples, and the dataset DbTests stay skipped.
- **Restart the backend after the build** so Flyway applies V9, then run the manual check in §9.4.

## 11. Risks and limits

- **Free quotas change.** Gemini 3.8 Flash is at 20 requests per day on this project. If Groq's limits
  drop too, `plateful.ai.order` and the model properties can switch to another free model without code
  changes. A paid fallback (the handoff names Claude Haiku 5.5) needs the user's approval first.
- **EEA rule.** Gemini's free tier may not be offered to users in the EEA, UK or Switzerland. North
  Macedonia is outside the EEA. If Plateful is ever deployed publicly, Gemini must be removed from
  `plateful.ai.order` or moved to a paid tier; this is noted for the deployment round.
- **Request time.** Up to 75 s in the worst case. If this proves too slow in practice, a job-and-poll
  design can replace the synchronous call without changing `RecipeAi`.
- **The checks are only as good as `food-rules.csv`.** Blocking too much is safe; blocking too little is
  not. The AI label tells the user to use their judgement.

## 12. Out of scope

- Photo to recipe (sub-project 3), Instagram import (sub-projects 4 and 5).
- Publishing, editing or rating AI recipes; AI-generated images.
- A database picker for daily meals.
- Docker, CI/CD and deployment.
