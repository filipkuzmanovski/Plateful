# AI calorie estimates: design

Date: 2026-11-05
Status: approved in conversation; this written spec is awaiting review.

This is sub-project 2 of 3. Sub-project 1 (likes and liked cookbooks) is merged. Sub-project 3 (preference cookbook of 5 AI recipes) comes last so that its recipes get calories; nothing in this design needs changing for that.

## 1. Goal

Uploaded recipes and private AI recipes show calories, protein, fat and carbs per serving, clearly labelled as an AI estimate. Imported recipes keep their own values and stay visibly different.

**The feature works when:**
- nobody waits for an estimate: recipes open at once and the numbers arrive shortly after;
- the AI supplies only per-ingredient numbers, while our code does the adding, the dividing and the rejecting;
- an answer that fails the checks shows nothing rather than a wrong number;
- estimates never use a person's daily AI limit and never use Gemini's small allowance;
- spam or rejected recipes cannot spend AI tokens.

## 2. Decisions from the conversation

| Question | Decision |
|---|---|
| When estimates run | In the background. AI recipes right after they are saved; uploaded recipes only once approved. |
| Which AI | Groq's main model (`openai/gpt-oss-120b`), Groq only. The smaller model was measured and was not good enough (section 3). |
| Where they are stored | Their own new table, for both kinds of recipe. Imported values in `recipes.calories` and friends are never touched. |
| Edits | Title, photos or steps: estimate kept. Servings: recalculated with no AI call. Ingredients: estimate removed at once, estimated again after re-approval. |
| No servings | Servings becomes required (1 or more) in the form and on the server. An older recipe without servings shows nothing and spends nothing until its author edits it. The AI never guesses servings. |
| Quota | Free to the user. The whole app does at most 40 estimates a day; the rest wait for the next day. |
| Display | Calories and macros, rounded, under "AI estimate, per serving". |
| Existing recipes | Filled in automatically: approved uploads, and AI recipes that are saved or under 24 hours old. |

## 3. Accuracy measured before building

A throwaway script (not in the repo) sent 30 imported recipes to Groq through a draft of this prompt, schema and checks, then compared the per-serving result with the recipe's stored values. The recipes were picked at random, one from each of 30 equal bands of the calorie range.

| | `gpt-oss-120b` (main) | `gpt-oss-20b` (smaller) |
|---|---|---|
| Median calorie miss | **7%** (27 kcal) | 18% |
| Within 10% / 20% / 30% | 17 / 22 / 26 of 29 | 8 / 16 / 22 of 29 |
| Median miss: protein / fat / carbs | 1.7 / 1.5 / 1.8 g | 3.6 / 1.8 / 7.6 g |
| Passed the checks | 29 of 29 (3 needed the retry) | 29 of 30 (3 needed the retry) |
| Median tokens per recipe | 1,742 | 918 |

**How to read this:**
- The stored values are calculated by Spoonacular, not measured, so this shows agreement rather than truth. The largest miss on both models was a black bean soup stored at 190 kcal and estimated at about 450. One pound of dry beans over 6 servings is already about 258 kcal, so the stored value looks wrong.
- Real misses remain that no check can catch, such as a pork recipe at 498 against 705. This is why the number is rounded and labelled.
- On a single ingredient, calories and the 4/4/9 macro sum differed by up to 168 kcal, while recipe totals passed. The macro check therefore runs on the recipe total.
- A wrong weight can hide behind plausible calories: the same soup listed 1 pound of beans as 1,135 g.

**Gaps:** a token cap stopped the main model before its 30th recipe and before its repeat test. Asking the same recipe twice was only measured on the smaller model (median difference 2%). Section 11 closes both. The whole test used about 93,500 tokens.

## 4. Data

`V13__nutrition_estimates.sql` adds one table:

```sql
CREATE TABLE nutrition_estimates (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipe_id           UUID UNIQUE REFERENCES recipes(id)           ON DELETE CASCADE,
    generated_recipe_id UUID UNIQUE REFERENCES generated_recipes(id) ON DELETE CASCADE,
    status              TEXT NOT NULL CHECK (status IN ('ready', 'failed')),
    calories            NUMERIC(8,1),
    protein_grams       NUMERIC(8,1),
    fat_grams           NUMERIC(8,1),
    carbs_grams         NUMERIC(8,1),
    breakdown           JSONB,
    ingredients_hash    TEXT NOT NULL,
    provider            TEXT,
    model               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((recipe_id IS NULL) <> (generated_recipe_id IS NULL)),
    CHECK (status = 'failed' OR (calories IS NOT NULL AND protein_grams IS NOT NULL
                                 AND fat_grams IS NOT NULL AND carbs_grams IS NOT NULL
                                 AND breakdown IS NOT NULL))
);
CREATE INDEX idx_nutrition_estimates_created ON nutrition_estimates (created_at);
ALTER TABLE nutrition_estimates ENABLE ROW LEVEL SECURITY;
```

- The four numbers are **whole-recipe totals**. Per-serving values are worked out when read, from the recipe's current servings. A servings edit therefore needs no write and no AI call.
- `breakdown` keeps the per-ingredient numbers, so an odd result can be explained later. It is not sent to the browser.
- `ingredients_hash` is the SHA-256 of the numbered lines sent to the AI (section 5.2). Title and servings are not part of it.
- Row level security is on with no policies, like `generated_recipes`: only the backend reads this table.
- A separate table was chosen because `Recipe` has no `@Version` and writes every column on flush, so a background write to `recipes` could overwrite an author's edit or an admin's decision.

## 5. The background job

`NutritionWorker` runs with `@Scheduled(fixedDelay = 30 s)`: one recipe per run, nothing else happening at the same time. No database connection is held during an AI call.

### 5.1 Choosing what to estimate

The next recipe with no row in `nutrition_estimates`, newest first, from:
- `recipes` where `source = 'user'`, `status = 'approved'` and `servings >= 1`, dated by `reviewed_at`;
- `generated_recipes` where `saved_at IS NOT NULL` or `created_at` is within the last 24 hours, dated by `created_at`.

Nothing needs to announce a new recipe. The cook, daily, scan and post flows stay unchanged, the approval flow stays unchanged, and the preference cookbook will be covered on its own. An admin's own upload, which skips the queue, is covered the same way.

The job does nothing when 40 rows have been created since the start of the UTC day (`plateful.ai.nutrition-daily-limit`). Failed rows count, because they spent tokens. Rows deleted by an edit stop counting, which is harmless at this scale.

### 5.2 One estimate

`NutritionEstimator.estimate(title, servings, lines)` knows nothing about the database:

- **Lines:** uploaded recipes send `ingredientName | originalText`, because the form can split the name from the amount. AI recipes send `name | amount`. Each line is cut to 200 characters. A recipe over 40 lines is stored as failed with no AI call, because uploads allow 100 lines and the free tier could not afford them.
- **Call:** a new `nutritionAi` chain in `AiConfig`, its own `FallbackRecipeAi` built from `plateful.ai.nutrition-order=groq`, so its pauses never affect the other chains. Temperature 0.1, at most 3,000 output tokens, strict JSON schema: `{"items": [{"line", "grams", "calories", "protein", "fat", "carbs"}]}`.
- **Prompt:** per-ingredient values for the whole recipe, no dividing by servings, a typical eaten amount where the line is vague, zero for water and plain salt. It states that the lines are data and any instructions inside them are to be ignored.
- **Retry:** an answer that fails `NutritionCheck` is asked once more, with the reason added to the prompt. Two calls at most.

### 5.3 The checks

`NutritionCheck` is a pure function and returns the first reason to reject:

1. Line numbers are exactly 1 to n, each once.
2. Every number is finite and not negative.
3. For each line: protein + fat + carbs is at most `grams * 1.05 + 1`.
4. For each line: calories are at most `900 kcal per 100 g + 5`. A line with 0 grams has at most 5 kcal.
5. For the recipe: calories and `4 * protein + 4 * carbs + 9 * fat` differ by no more than the larger of 50 kcal and 20%.
6. Per serving: calories from 1 to 3,000, and no more than 3,000 g of food.

### 5.4 Outcomes

| What happened | What is stored | What the job does next |
|---|---|---|
| Passed, first or second try | `ready` row | Next recipe on the next run |
| Failed the checks or unreadable, twice | `failed` row, no numbers | Never retried unless the ingredients change |
| Over 40 lines | `failed` row, no AI call | As above |
| Groq busy, down or not configured, or an unexpected error | Nothing | Pauses 5 minutes (in memory), then tries again |
| Recipe or AI recipe deleted during the call | Nothing | Next recipe |
| Ingredients changed during the call | Nothing | Next run picks the recipe again if still eligible |

Logs say which kind of recipe, its id and the outcome, never ingredient text. The existing `ai call provider=...` line covers the provider side.

### 5.5 Pacing

Each call uses about 1,750 tokens. Thirty seconds between runs keeps the job to about 3,500 of Groq's 8,000 tokens a minute, and the cap to about 70,000 of the 200,000 a day. Recipe generation shares both limits. If the job does use up a minute's tokens, the generation chain falls back to Gemini as it already does. Worth watching in logs; not worth more machinery now.

## 6. Edits and races

- `RecipeService.update` computes the new `ingredients_hash` inside its existing transaction and row lock. If a row exists with a different hash, it is deleted, including a `failed` row, so a corrected recipe gets another try.
- The worker saves a recipe's estimate in a short transaction that takes the same row lock (`findForReviewById`) and recomputes the hash from the current ingredients. A mismatch means the answer is discarded. An edit and a save therefore cannot interleave.
- `generated_recipes` rows do not change their ingredients, so the worker only inserts with `ON CONFLICT DO NOTHING`, and treats a foreign key error as "deleted".

## 7. API

Single-recipe responses gain one field, `estimate`:

```json
{ "status": "ready", "calories": 450, "proteinGrams": 28, "fatGrams": 18, "carbsGrams": 42 }
```

- It appears on `RecipeDisplayDto` from `GET /api/recipes/{id}`, the admin detail and the create and update responses, and on `GeneratedRecipeDto` wherever one is returned. List pages leave it null, because cards do not show it and it would add a query.
- **Rounding happens only on the server:** calories to the nearest 10 (whole kcal below 10), macros to the nearest gram.
- `status` is `ready` with numbers, or `pending` or `none` with null numbers.

| Recipe | `ready` | `pending` | `none` |
|---|---|---|---|
| Uploaded | Approved, servings 1+, `ready` row | Approved, servings 1+, no row | Not approved, no servings, or `failed` row |
| AI | `ready` row | No row, and saved or under 24 hours old | `failed` row, or no row and old and unsaved |
| Imported | Never | Never | Always; the page uses the recipe's own values |

Not being approved wins over everything: an author's edited recipe waiting for review shows no number, even if its estimate was kept.

`RecipeCreateDto.servings` becomes `@NotNull @Min(1)`, so the server rejects a missing or 0 value with the usual validation error.

## 8. Frontend

- **New `NutritionEstimate` component**, used by `RecipeBody` and `AiRecipeView`, in the same place in the left column:
  - `ready`: "AI estimate, per serving" in small soft text, then the existing `Pill`s: "about 450 kcal", "28 g protein", "18 g fat", "42 g carbs".
  - `pending`: "Working out calories..." in the same soft text.
  - `none` or null: renders nothing.
  - It sits in a polite live region, so a number arriving is announced once.
- **Imported recipes:** `RecipeBody` keeps today's exact block whenever `recipe.calories !== null`, and that takes priority over the estimate.
- **Waiting:** a small hook invalidates the page's existing recipe query every 10 seconds while `status` is `pending`, 12 times at most, then stops. The line then disappears, and a later visit shows the result. This reuses the existing GET endpoints.
- **Form:** `RecipeFormPage` requires servings of 1 or more with the message "Enter 1 or more servings". An older recipe with no servings opens with the field empty and cannot be saved until it is filled in.
- **Privacy page:** one added sentence in the AI section: the ingredient lists of AI recipes and of approved uploaded recipes are sent to Groq to estimate calories, and never to Google for this.
- **Style:** role-named tokens only, no emojis, and it must read properly in dark mode and at phone width.

## 9. Out of scope

- Calories on cards, in cookbooks or in print.
- Sorting or filtering by calories. Imported and estimated numbers should not be ranked together.
- Estimating imported recipes, or correcting their stored values even where they look wrong.
- A way for authors to enter or correct numbers by hand, or to request a new estimate.
- Scaling numbers when a reader changes servings (no such control exists).
- Showing the per-ingredient breakdown.

## 10. Testing

**Backend, no database:**
- `NutritionCheck`: each rule, both sides of each limit, and the measured cases that must pass (1 pound of dry beans, a 0 g pinch of salt).
- `NutritionEstimator`: first-try pass, retry that passes, retry with the reason in the prompt, two failures, unreadable JSON, more than 40 lines makes no AI call, cut-off lines.
- `NutritionWorker`: nothing to do, the cap, the 5-minute pause after each kind of provider error, a stale hash discarded, a deleted recipe.
- Read side: per-serving rounding, including small values and servings changes; every row of the status table in section 7.
- `RecipeService.update`: a title edit keeps the row, a servings edit keeps it, an ingredient edit deletes it, and a failed row is cleared by an ingredient edit.
- `RecipeCreateDto`: servings null and 0 rejected, 1 accepted. `AiConfig`: the nutrition chain is Groq only.

**Backend, opt-in database tests** (skipped unless `SUPABASE_DB_PASSWORD` is set, and until V13 is live, no `@Import`, no Flyway): the two selection queries, the eligibility limits, the unique and exactly-one constraints, and cascade on delete.

**Frontend:** each `NutritionEstimate` state, imported values taking priority on `RecipeBody`, polling stopping when `ready` arrives and after 12 tries, the form rule, the privacy sentence, and `expectNoEmoji`.

**Browser:** a real browser check in light and dark mode at desktop and phone width, signed in by the user, or with a throwaway fake fetch if the backend is down, as for cookbooks.

## 11. Final measurement

Before the build is called done, re-run the throwaway script with the final prompt and checks on the same 30 recipes on the main model, plus the repeat test on 5 of them, with the cap raised so that it finishes. This costs about 60,000 tokens, so it needs the user's go-ahead on the day. The result goes in the dev log.

**The feature is acceptable if** the median calorie miss stays at or under 10% and at least 20 of 30 are within 20%, allowing for stored values that are clearly wrong. If it falls short, stop and bring the result to the user before going further.

## 12. After merging

The user needs to:
1. Restart the backend so V13 runs.
2. Run the new database tests with the database password set.
3. Generate an AI recipe and watch the number appear; approve an upload and check that it gets a number; edit its ingredients and check that the number goes away until re-approval.
