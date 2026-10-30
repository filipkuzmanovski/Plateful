# Food preferences and recipe dataset: design

Date: 2026-10-30
Status: approved in conversation; this written spec is awaiting review.
Sub-project 1 of the AI features (see `docs/ai-features-handoff.md`). No AI calls yet.

## 1. Goal

Lay the groundwork the AI features stand on:

1. **Food preferences.** Every new user tells Plateful, during sign-up, about their allergies, diet, dislikes and likes. The data is stored and can be edited from the profile. Later, allergies and dislikes become hard filters on everything the AI makes.
2. **A reference dataset.** About 50,000 recipes from RecipeNLG, stored behind the scenes and never shown publicly. They are the AI's source of inspiration.
3. **Pairing statistics from all 2.2 million RecipeNLG recipes.** For each common ingredient: how many recipes contain it, and which ingredients appear with it most often. Later prompts quote these facts, so every recipe in the dataset informs the AI, not only the few examples sent per request.

## 2. Decisions from the conversation

| Question | Decision |
|---|---|
| Order of work | AI features first. Public deployment is optional; a Docker setup plus CI/CD (GitHub Actions) is an acceptable showcase and becomes its own round after the AI work. |
| Dataset | **RecipeNLG.** Its terms allow non-commercial research and education. Its recipes are never displayed publicly. |
| Preference contents | Allergies from the 14 EU allergens (fixed list). Diet: vegetarian, vegan, gluten-free, dairy-free. Dislikes: free-text ingredient chips with suggestions. Likes: cuisines (existing picker) plus optional ingredient chips. Allergies, diet and dislikes are hard filters; likes only boost. |
| Required? | Yes, for every new user. Diet, dislikes and likes may be empty. Allergies need an explicit answer: ticked allergens, or "I have no food allergies". |
| Existing users | The user deletes all existing accounts in Supabase Authentication → Users and starts fresh (manual step, §9). No banner or migration path for old accounts. |
| Daily meals | Always AI-generated (sub-project 2). There is **no database picker**. When the free quota is used up, the user sees their most recent generated set, labelled with its date; a brand-new user with none sees a "try again later" message. |
| Using "every recipe" | Per request, the backend searches all 50,000 reference recipes and sends the best 5 to 8 as examples; pairing statistics computed over all 2.2 million add dataset-wide knowledge. |
| Pipeline | Approach 1: offline Python preparation, a separate insert-only loader, separate tables. |

## 3. Data model: Flyway `V8__food_preferences_and_reference_data.sql`

```sql
CREATE TABLE user_preferences (
    user_id              UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    allergens            TEXT[] NOT NULL DEFAULT '{}',  -- allergen keys from food-rules.csv
    diets                TEXT[] NOT NULL DEFAULT '{}',  -- vegetarian | vegan | gluten_free | dairy_free
    disliked_ingredients TEXT[] NOT NULL DEFAULT '{}',  -- normalised names
    liked_ingredients    TEXT[] NOT NULL DEFAULT '{}',  -- normalised names
    liked_cuisines       TEXT[] NOT NULL DEFAULT '{}',  -- keys from cuisines.csv
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reference_recipes (
    id               BIGINT PRIMARY KEY,               -- RecipeNLG row id
    title            VARCHAR(255) NOT NULL,
    ingredient_lines TEXT[] NOT NULL,                  -- with quantities, as in the dataset
    ingredient_names TEXT[] NOT NULL,                  -- normalised, from the NER column
    directions       TEXT[] NOT NULL,
    meal_types       TEXT[] NOT NULL DEFAULT '{}',     -- breakfast | lunch | dinner
    allergens        TEXT[] NOT NULL DEFAULT '{}',     -- allergen keys found, computed at import
    vegetarian       BOOLEAN NOT NULL,
    vegan            BOOLEAN NOT NULL,
    gluten_free      BOOLEAN NOT NULL,
    dairy_free       BOOLEAN NOT NULL
);
CREATE INDEX idx_reference_recipes_names ON reference_recipes USING GIN (ingredient_names);
CREATE INDEX idx_reference_recipes_meals ON reference_recipes USING GIN (meal_types);

CREATE TABLE ingredient_stats (
    name         TEXT PRIMARY KEY,                     -- normalised
    recipe_count INTEGER NOT NULL                      -- over all 2.2 million recipes
);
CREATE INDEX idx_ingredient_stats_prefix ON ingredient_stats (name text_pattern_ops);

CREATE TABLE ingredient_pairings (
    ingredient     TEXT NOT NULL,
    partner        TEXT NOT NULL,
    together_count INTEGER NOT NULL,
    PRIMARY KEY (ingredient, partner)
);

ALTER TABLE user_preferences    ENABLE ROW LEVEL SECURITY;
ALTER TABLE reference_recipes   ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingredient_stats    ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingredient_pairings ENABLE ROW LEVEL SECURITY;
```

- **Meaning of a preferences row:** if it exists, the user has answered. An empty `allergens` then means "no food allergies". There is no separate "answered" flag.
- **Pairings are stored in both directions** (A→B and B→A), so "partners of X" is one primary-key range scan. `P(partner | ingredient) = together_count / ingredient_stats.recipe_count`.
- **`allergens` on `reference_recipes`** is computed at import with the shared rules (§4). That keeps the later hard-filter query simple: `NOT (allergens && :userAllergens)`.
- **No public policies** on any of the four tables. The backend connects as the table owner, as for every table.

## 4. Shared vocabulary: `food-rules.csv`

The file lives in `RecipeAppBackend/src/main/resources/food-rules.csv`. The Python scripts read the same file by relative path, so there is one source of truth.

Columns: `kind,key,label,terms,exceptions`

- **`kind`:** `allergen`, `diet` or `meal`.
- **`terms` and `exceptions`:** `|`-separated. A term written `@key` pulls in the terms of another row.
- **Allergen rows:** the 14 EU allergens, with keys `gluten`, `crustaceans`, `eggs`, `fish`, `peanuts`, `soy`, `milk`, `tree_nuts`, `celery`, `mustard`, `sesame`, `sulphites`, `lupin`, `molluscs`.
  - Example: `allergen,milk,Milk,milk|butter|cheese|cream|yogurt|yoghurt|whey|ghee|...,peanut butter|almond butter|coconut milk|oat milk|soy milk|almond milk|cocoa butter`.
- **Diet rows** say what each diet excludes:
  - `vegetarian`: meat, poultry, fish and seafood terms, and gelatin;
  - `vegan`: `@vegetarian|@milk|@eggs|honey`;
  - `gluten_free`: `@gluten`;
  - `dairy_free`: `@milk`.
- **Meal rows** list title keywords:
  - `breakfast`: pancake, omelette, omelet, granola, porridge, oatmeal, muffin, waffle, french toast, frittata, smoothie...
  - `lunch`: salad, sandwich, soup, wrap, quiche, panini, burger...
  - `dinner`: roast, curry, stew, casserole, pasta, lasagna, risotto, chili, pie...

  A title may match several meals.

**Matching rule.** A term matches an ingredient name when every word of the term appears as a whole word in the name, in order. For example, `butter` matches `unsalted butter` but not `butternut squash`. Exceptions are checked first: a name that matches an exception is not counted for that row. Exceptions stay deliberately short, because blocking too much is safe and blocking too little is not.

## 5. Dataset pipeline: `tools/dataset/`

Python 3 (3.14 is installed). Dependencies go in `tools/dataset/requirements.txt`: `psycopg[binary]` and `pytest`. These paths are added to `.gitignore`:
- `tools/dataset/data/`, where the user puts `full_dataset.csv`;
- `tools/dataset/out/`.

The dataset and the generated files are never committed.

### 5.1 `normalise.py`

`normalise(name) -> str | None`:
1. lowercase, and turn hyphens into spaces;
2. remove anything that isn't a letter or a space, collapse spaces, and trim;
3. singularise the last word:
   - `ies` → `y`;
   - `oes` → `o`;
   - `ches`, `shes`, `sses`, `xes` → drop `es`;
   - a trailing `s` is dropped unless the word ends in `ss`, `us` or `is`, or is on a short invariant list (`asparagus`, `hummus`, `couscous`, `molasses`, `swiss`, `grits`, `series`).

Empty results and results longer than 60 characters return `None`.

The cases live in `tools/dataset/normalise-cases.csv` (input, expected). **Both** pytest and the Java `IngredientNamesTest` run that file, so the two implementations can't drift apart.

### 5.2 `prepare.py`

It reads `data/full_dataset.csv` row by row with the standard `csv` module. The list columns are parsed with `json.loads`.

1. **Pass 1:** normalise each recipe's `NER` names, dropping duplicates within a recipe. Count the recipes per name (`ingredient_stats`, kept when the count is 5 or more).
2. **Pass 2:** for recipes whose names include two or more "common" ingredients (count ≥ 50), count every unordered pair among those common names. For each ingredient, keep its top 30 partners by count. Write them in both directions.
3. **Pass 3, tag and filter:**
   - **meal types** come from the title, using the `meal` rows;
   - **allergens and diet flags** come from the ingredient names, using the `allergen` and `diet` rows;
   - **a recipe is kept only if:**
     - it has a non-empty title of 255 characters or less;
     - it has 3 to 20 ingredient lines;
     - it has 2 to 25 directions;
     - each direction is 2,000 characters or less;
     - its ingredient names are non-empty;
     - its normalised title hasn't already been kept.
4. **Sample** with `random.Random(20261030)`:
   - up to 15,000 recipes each tagged breakfast, lunch and dinner (a recipe with several tags counts toward the first bucket it fills);
   - plus 5,000 untagged recipes;
   - if a bucket runs short, the gap is filled from untagged recipes, so the total stays about 50,000.
5. **Output:** write `out/reference_recipes.csv`, `out/ingredient_stats.csv` and `out/ingredient_pairings.csv` (arrays as Postgres array literals). Print counts per bucket and the time taken.

### 5.3 `load.py`

- **Connection:**
  - the host, port and user come from `application.properties` (read-only parse);
  - the password comes from the `SUPABASE_DB_PASSWORD` environment variable and is never printed;
  - it uses one connection.
- **Before writing:**
  - check that V8's tables exist (that is, the backend has restarted since V8 was compiled); stop if they don't;
  - print `pg_database_size` and estimate the projected size as the current size plus 2.5 × the CSV bytes;
  - refuse if that's over 400 MB.
- **Load:** for each table, `COPY` into a temporary table, then `INSERT ... SELECT ... ON CONFLICT DO NOTHING`. The whole load is one transaction.
- **After loading:** print the rows inserted and skipped per table, and the size after.
- **`--dry-run`:** connects, checks, prints counts and the projected size, and writes nothing.
- **No delete, truncate or replace option exists.**

### 5.4 Python tests (`tools/dataset/tests/`)

- **Normaliser:** the shared cases file.
- **Rule matching:** whole words, exceptions and `@key` expansion.
- **Meal tagging and diet flags.**
- **Pair counting and the top-30 cut,** on a 20-row fixture.
- **Filter and sample:** reproducible with the seed, and bucket fill-in.
- **`load.py` argument parsing and size guard,** with the connection mocked.

## 6. Backend

### 6.1 Package `food`

- **`FoodRules`** (a bean) loads `food-rules.csv` at startup and fails fast on an unknown `@key` or a duplicate key. It exposes:
  - `allergens()`: the ordered key and label pairs;
  - `allergenKeys()` and `dietKeys()`;
  - `allergensIn(Collection<String> names)`: the set of allergen keys;
  - `dietViolations(Collection<String> names, Set<String> diets)`;
  - `mealTypesFor(String title)`;
  - `excludedTerms(Set<String> allergens, Set<String> diets)`.
- **`IngredientNames.normalise(String)`** is the Java twin of §5.1.

### 6.2 Preferences

- **Entity and repository:** a `UserPreferences` entity (`text[]` mapped like `Recipe.cuisines`) and `UserPreferencesRepository`.
- **`PreferencesService.get(userId)`** throws `ResourceNotFoundException` when there is no row.
- **`PreferencesService.save(userId, dto)`** validates, normalises, removes duplicates and upserts. The rules:
  - allergen and diet keys must be known (400 otherwise);
  - cuisines are checked with `CuisineCatalog` (the existing `InvalidCuisineException`);
  - ingredient names are normalised, and names that normalise to nothing are dropped;
  - limits: up to 30 disliked and 30 liked ingredients, and up to 20 cuisines (400 above that).
- **`PreferencesController`:**
  - `GET /api/users/me/preferences` returns 200 with a `PreferencesDto`, or 404;
  - `PUT /api/users/me/preferences` takes a `PreferencesDto` and returns 200 with the saved DTO.

  Both are under `me`, so `MfaEnforcementFilter` already applies.
- **`MyProfileDto`** gains `boolean hasPreferences`, from `existsById`.

### 6.3 Food lookups (public)

- `GET /api/food/allergens` returns `[{ key, label }]` from `FoodRules`.
- `GET /api/ingredients/suggest?q=` normalises `q`; if that's empty or shorter than 2 characters it returns `[]`. Otherwise it returns up to 10 names from `ingredient_stats` where `name LIKE :q || '%'` (LIKE-escaped), ordered by `recipe_count DESC`.
- `SecurityConfig` permits these GETs.

### 6.4 Groundwork for sub-project 2 (no endpoint)

`ReferenceDataRepository.findClosest(wanted, mealType, excludedAllergens, diets, dislikes, limit)` is a native query (it lives in `ReferenceDataRepository`, next to the ingredient lookups; it normalises `wanted` and `dislikes` itself):
- **candidates:** `ingredient_names && :wanted`, plus `:mealType = ANY(meal_types)` when one is given;
- **hard filters:**
  - `NOT (allergens && :excludedAllergens)`;
  - the diet flags;
  - for each dislike, no ingredient name matches it as a whole word (`~ ('\m' || :dislike || '\M')`, with regex characters escaped; names are letters and spaces only after normalisation anyway);
- **ranking:**
  1. overlap count, highest first;
  2. then fewer total ingredients (closer to Jaccard);
  3. then `id`, so the order is stable.

### 6.5 Backend tests

- **Unit tests:**
  - `FoodRulesTest`: loads the real file, whole-word matching, exceptions, `@key` expansion, meal tags;
  - `IngredientNamesTest`: the shared cases file.
- **Service and controller tests,** in the style of the existing ones:
  - `PreferencesServiceTest` and `PreferencesControllerTest`: 404 before saving; validation errors; the limits; normalisation and duplicate removal; 2FA guarding through the existing filter test pattern;
  - `FoodControllerTest`;
  - `UserController` `hasPreferences`.
- **DbTests,** with exactly the annotations of `RecipeCuisineQueryDbTest`:
  - `ReferenceRecipeQueryDbTest`: `findClosest` returns overlap-ranked results, and none contain an excluded allergen, a broken diet or a disliked word;
  - `IngredientSuggestDbTest`.

  These need the data loaded; until then they are skipped with an assumption, as the existing known-skip does.

## 7. Frontend

- **Types and API:**
  - `lib/types.ts`: `MyProfile.hasPreferences`, `Preferences`, `Allergen`;
  - `api/preferences.ts`: `getPreferences`, `savePreferences`;
  - `api/food.ts`: `getAllergens`, `suggestIngredients`.
- **Sign-in state:**
  - `AuthProvider` gets a new `needs-preferences` status, used when the profile loads with `hasPreferences === false`;
  - `ProtectedRoute` redirects that status to `/preferences/setup`, as `needs-profile` goes to `/complete-profile`;
  - `CompleteProfilePage` keeps its flow: after `setProfile`, the status becomes `needs-preferences`, and navigation goes to `/preferences/setup`.
- **`features/preferences/PreferencesForm.tsx`** is shared by the two pages below. It has four sections:
  1. **Allergies:**
     - a fieldset with a checkbox grid of the 14 allergens (from `/api/food/allergens`) and a separate "I have no food allergies" checkbox;
     - ticking either one clears the other;
     - one of them is required; otherwise an error is shown on the group only (the pattern from the 2026-10-15 polish).
  2. **Diet:** four checkboxes.
  3. **Ingredients I don't eat:** `IngredientChipInput`. It has a 300 ms debounced suggestion list from `/api/ingredients/suggest`; Enter or comma adds the typed text; chips have remove buttons; the maximum is 30.
  4. **What I like:** the existing `CuisinePicker` (maximum 20) and a second `IngredientChipInput` (maximum 30).
- **Pages:**
  - `PreferencesSetupPage` at `/preferences/setup`, inside `AuthCard`, with the title "Your food preferences" and a note that allergies are always filtered out. Saving goes to `/`.
  - The profile gets a third tab, **Preferences**, at `?tab=preferences`, with the same form, a Save button and a sonner toast. `ProfileTabs` is extended accordingly.
- **Privacy:** `PrivacyPage` gets a paragraph saying:
  - allergies and diet are stored;
  - they are used only to filter and personalise recipe suggestions;
  - they're editable on the profile;
  - they're deleted with the account.

  The AI provider paragraph follows in sub-project 2.
- **Rules:** no emojis anywhere. Every new screen's tests call `expectNoEmoji()`.

### 7.1 Frontend tests (Vitest and MSW)

- **The setup redirect:** a `needs-preferences` user going to `/` lands on `/preferences/setup`.
- **The allergy requirement:** the group error appears, and the two choices clear each other.
- **Chips:** adding from a suggestion, adding typed text, removing, and the maximum.
- **The PUT payload,** and the status becoming `ready` after saving.
- **The profile tab:** loads saved values, saves, shows a toast, and keyboard navigation across three tabs works.
- **Emoji checks:** `expectNoEmoji()`.

## 8. Error handling

- **The suggest endpoint** fails soft on the frontend: no suggestion list, and typing still works.
- **A failed preference save** shows the server's `detail` in a `FormError`, as `CompleteProfilePage` does.
- **The loader** exits non-zero with a clear message if:
  - the tables are missing;
  - the size guard trips;
  - a CSV is malformed (the transaction rolls back, and nothing is partly loaded).

## 9. Manual steps for the user

1. **Before the import:** accept the RecipeNLG terms on its site or on Hugging Face, and download `full_dataset.csv` into `tools/dataset/data/`. The plan will give the exact links and steps.
2. **After V8 is on the live database** (restart the backend after the compile):
   1. run `python prepare.py`;
   2. then `python load.py --dry-run`;
   3. then `python load.py`.
3. **Before testing the new sign-up flow:**
   1. delete all users in Supabase **Authentication → Users**, not only `public.users`;
   2. sign up again;
   3. set `is_admin` with SQL;
   4. enrol 2FA;
   5. optionally, remove the old user folders from the storage buckets.

   What deleting the users does:
   - recipes written by those users stay, with `user_id` set to null;
   - their ratings, comments, reactions and saves are deleted.

## 10. Out of scope

- Any AI call, the provider interface, rate limiting, the novelty check and the daily meals. All of these are sub-project 2.
- Showing reference recipes to users, anywhere.
- A meal-type field on Plateful's own recipes. It isn't needed now that the daily meals are always generated.
- Docker and CI/CD: a separate round after the AI work.

## 11. Carried into sub-project 2

- **Reference tags are frozen at import.** `load.py` only inserts, so the `allergens` and diet flags of a
  `reference_recipes` row stay as `prepare.py` computed them, even after `food-rules.csv` is fixed later.
- **So sub-project 2 re-checks every candidate in Java.** Each recipe `ReferenceDataRepository.findClosest`
  returns must be checked again with `FoodRules.allergensIn` and `FoodRules.dietViolations` on its
  `ingredient_names`, and dropped if it breaks the user's allergens or diets. This is defence in depth:
  the SQL filter uses the frozen tags, the Java check uses the current rules, so later rule fixes take
  effect without re-importing.
- **Name.** The repository is `ReferenceDataRepository` (section 6.4 first called it
  `ReferenceRecipeRepository`).
