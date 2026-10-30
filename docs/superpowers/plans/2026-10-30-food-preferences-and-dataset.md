# Food Preferences and Recipe Dataset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every new user records allergies, diet, dislikes and likes during sign-up. Plateful also holds a private 50,000-recipe RecipeNLG reference set and ingredient pairing statistics computed from all 2.2 million recipes, ready for the AI features.

**Architecture:**
- **Offline pipeline:** Python scripts under `tools/dataset/` prepare the dataset offline and load it insert-only into four new Flyway V8 tables.
- **Shared rules:** one shared rules file (`food-rules.csv`) and one irregular-plurals file are read by both Python and Java, so allergen, diet and name normalisation agree in both languages.
- **Backend:** Spring Boot gets a `food` package, a preferences API under `/api/users/me/preferences`, public food lookups, and a JDBC repository for reference queries.
- **Frontend:** React gets a `needs-preferences` sign-in state, a setup page, and a profile tab.

**Tech Stack:**
- Python 3.14 (stdlib `csv`/`json`/`random`/`unicodedata`, `psycopg[binary]`, `pytest`);
- Spring Boot 4 / Java 21 / Hibernate 7 / Flyway / `NamedParameterJdbcTemplate`;
- React 19 / TypeScript / TanStack Query / Vitest / MSW.

**Spec:** `docs/superpowers/specs/2026-10-30-food-preferences-and-dataset-design.md`

## Global Constraints

- **Branch:** `feature/food-preferences`.
- **Commits:**
  - stage explicit paths only;
  - every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`;
  - push after each task.
- **Before ANY backend compile,** run the following; if it prints `True`, STOP and tell the user:
  `powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'RecipeAppBackendApplication' } | ForEach-Object { \$_.CommandLine -match 'spoonacular' }"`
- **Backend tests:**
  - `export JAVA_HOME="$USERPROFILE/.jdks/ms-21.0.9"`, then from `RecipeAppBackend/` run `./mvnw -q test -Dtest=<Classes>`;
  - **never unfiltered**;
  - clear `target/surefire-reports` first and read the counts from it.
- **DbTests:**
  - use exactly the annotations of `RecipeCuisineQueryDbTest` (`@Tag("db")`, `@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")`, `@DataJpaTest`, `@AutoConfigureTestDatabase(replace = NONE)`, `@TestPropertySource(properties = "spring.flyway.enabled=false")`);
  - write only inside the rolled-back test transaction;
  - export the password with the handoff's python one-liner, and never print it.
- **Frontend:** from `recipe-app-frontend/`, both `npx vitest run` and `npm run build` must be green before each frontend commit.
- **Never:**
  - print secrets;
  - edit `.idea/`;
  - apply migration SQL by hand;
  - add a delete, truncate or replace mode to any loader.
- **No emojis** in any UI text. New screens' tests call `expectNoEmoji()`.
- **Dataset files are never committed:** `tools/dataset/data/` and `tools/dataset/out/` are git-ignored.
- **Allergen keys, in this order:** `gluten, crustaceans, eggs, fish, peanuts, soy, milk, tree_nuts, celery, mustard, sesame, sulphites, lupin, molluscs`.
- **Diet keys:** `vegetarian, vegan, gluten_free, dairy_free`. **Meal keys:** `breakfast, lunch, dinner`.
- **Preference limits:**
  - 30 disliked ingredients;
  - 30 liked ingredients;
  - 20 liked cuisines;
  - 60 characters per normalised name.
- **Sampling:**
  - seed `20261030`;
  - up to 15,000 recipes per meal bucket, plus 5,000 untagged; shortfalls are filled from untagged;
  - "common" ingredient: count ≥ 50; top 30 partners each; `ingredient_stats` keeps names with count ≥ 5.
- **Loader size guard:** refuse if the current DB size + 2.5 × the CSV bytes is over 400 MB.

## Rulings made while planning (spec clarifications)

1. **Accents are folded.** Normalisation applies NFKD and drops combining marks, so "jalapeño" and "jalapeno" match. Without this, a typed dislike could miss dataset names.
2. **Irregular plurals live in one shared file,** `RecipeAppBackend/src/main/resources/ingredient-irregulars.csv` (`plural,singular`), read by both languages. Examples: leaves→leaf, cookies→cookie; molasses→molasses keeps invariant words unchanged.
3. **Term matching is a contiguous whole-word match:** `" " + name + " "` contains `" " + term + " "`. Terms are normalised when loaded. An `@key` reference imports that row's terms **and** its exceptions.
4. **Meal tagging (Python only)** singularises every word of the title, not only the last. Java `mealTypesFor` and `excludedTerms` are left out until sub-project 2 needs them (YAGNI).
5. **Pairings:** one row per (ingredient, top partner), taken from each ingredient's own top-30 list. Lookup is `WHERE ingredient = ?`.
6. **Reference queries use `NamedParameterJdbcTemplate`, not JPA entities.** `reference_recipes`, `ingredient_stats` and `ingredient_pairings` get no `@Entity`. Only `user_preferences` is an entity.
7. **Dislike filter:** `(' ' || n || ' ') LIKE ANY(:patterns)` with patterns `'% word %'`. Normalised names hold only `[a-z ]`, so no escaping is needed.
8. **DbTests insert their own fixture rows** (negative ids) inside the rolled-back transaction, so they don't depend on the real import.
9. **`CuisinePicker` gets an optional `max` prop** (default 10, as today). Preferences pass 20.
10. **Onboarding redirect on public pages:**
    - an `OnboardingRedirect` in `AppShell` sends `needs-preferences` users to `/preferences/setup` from any page except `/preferences/setup`, `/privacy`, `/terms`, `/about` and `/contact`;
    - `ProtectedRoute` gets the same branch;
    - `needs-profile` behaviour is unchanged.

## Review Focus

1. **Accented or mixed-case input** ("Jalapeño", "CRÈME fraîche") must normalise identically in Python and Java. Pinned by `normalise-cases.csv` rows in Task 1.
2. **A peanut-allergic user and "peanut butter":** the milk exception must not hide the peanut match. Pinned in Task 2 (Python) and Task 5 (Java).
3. **A malformed dataset row** (bad JSON in a list column, an empty NER) is skipped and counted, and `prepare.py` doesn't crash. Pinned in Task 3.
4. **Suggest called with `%`, `_` or a single letter** returns `[]`, not the whole table. Pinned in Task 7.
5. **Saving preferences twice, or re-opening the profile tab,** keeps one row, shows the saved values, and "no allergies" stays ticked. Pinned in Tasks 6 and 10.

---

### Task 1: Dataset tooling skeleton and the shared normaliser (Python)

**Files:**
- Create: `tools/dataset/requirements.txt` (`psycopg[binary]>=3.2`, `pytest>=8`)
- Create: `tools/dataset/normalise.py`
- Create: `tools/dataset/normalise-cases.csv`
- Create: `RecipeAppBackend/src/main/resources/ingredient-irregulars.csv`
- Create: `tools/dataset/tests/test_normalise.py`
- Modify: `.gitignore` (add `tools/dataset/data/`, `tools/dataset/out/`, `tools/dataset/**/__pycache__/`, `tools/dataset/.pytest_cache/`)

**Interfaces:**
- Produces:
  - `normalise(name: str) -> str | None`: the ingredient form, singularising the last word;
  - `normalise_words(text: str) -> str`: the title form, singularising every word and never returning `None`;
  - `REPO_ROOT: Path`, the repository root, resolved from `__file__`.

- [ ] **Step 1: Write `normalise-cases.csv`** (header `input,expected`; an empty `expected` means `None`). It needs at least these rows:
  `Tomatoes,tomato` · `Garlic cloves,garlic clove` · `berries,berry` · `peaches,peach` · `radishes,radish` · `molasses,molasses` · `bay leaves,bay leaf` · `cookies,cookie` · `asparagus,asparagus` · `hummus,hummus` · `swiss,swiss` · `Jalapeño,jalapeno` · `CRÈME fraîche,creme fraiche` · `extra-virgin olive oil,extra virgin olive oil` · `  salt   & pepper ,salt pepper` · `100% juice,juice` · `!!!,` · a 61-character input → empty.
- [ ] **Step 2: Write `ingredient-irregulars.csv`** (header `plural,singular`):
  - leaves,leaf
  - halves,half
  - loaves,loaf
  - knives,knife
  - cookies,cookie
  - pies,pie
  - brownies,brownie
  - smoothies,smoothie
  - veggies,veggie
  - molasses,molasses
  - grits,grits
  - schnapps,schnapps
- [ ] **Step 3: Write `test_normalise.py`.** It runs every row of the cases file through `normalise` (parametrised). It also checks `normalise_words("Fluffy Pancakes with Berries") == "fluffy pancake with berry"`.
- [ ] **Step 4: Run** `python -m pytest tools/dataset/tests -q` from the repo root. Expected: FAIL (module missing).
- [ ] **Step 5: Implement `normalise.py`:**
  1. NFKD, then drop combining marks;
  2. lowercase, and hyphens become spaces;
  3. `re.sub(r"[^a-z ]", "", s)`, collapse spaces, trim;
  4. singularise the word: irregulars first; then `ies→y`, `oes→o`; `ches|shes|sses|xes` drop `es`; a trailing `s` is dropped unless the word ends in `ss|us|is`;
  5. `None` if the result is empty or longer than 60 characters.

  Irregulars are loaded from `REPO_ROOT / "RecipeAppBackend/src/main/resources/ingredient-irregulars.csv"`.
- [ ] **Step 6: Run the tests.** Expected: all PASS.
- [ ] **Step 7: Commit** `feat(dataset): shared ingredient-name normaliser` with the files above.

### Task 2: Shared food rules file and Python rule matching

**Files:**
- Create: `RecipeAppBackend/src/main/resources/food-rules.csv`
- Create: `tools/dataset/rules.py`
- Create: `tools/dataset/tests/test_rules.py`

**Interfaces:**
- Consumes: `normalise`, `normalise_words` (Task 1).
- Produces:
  - `FoodRules.load(path: Path | None = None) -> FoodRules`;
  - `.allergen_keys -> list[str]` (file order);
  - `.allergens_in(names: Iterable[str]) -> set[str]`;
  - `.diet_flags(names: Iterable[str]) -> dict[str, bool]` (keys = diet keys; `True` = recipe fits the diet);
  - `.meal_types(title: str) -> list[str]` (file order).

- [ ] **Step 1: Write `food-rules.csv`** with header `kind,key,label,terms,exceptions` and 14 allergen rows in the Global Constraints order. Labels:
  - Cereals containing gluten
  - Crustaceans
  - Eggs
  - Fish
  - Peanuts
  - Soybeans
  - Milk
  - Tree nuts
  - Celery
  - Mustard
  - Sesame
  - Sulphites
  - Lupin
  - Molluscs

  Then 4 diet rows and 3 meal rows, per spec §4. Term lists:
  - **Generous:** for example, milk includes butter, cheese, cream, yogurt, yoghurt, whey, ghee, buttermilk, mozzarella, parmesan, ricotta, mascarpone, casein, lactose.
  - **Short exceptions:** for example, milk excepts peanut butter, almond butter, cashew butter, cocoa butter, coconut milk, coconut cream, oat milk, soy milk, almond milk, rice milk.
  - **Vegetarian terms** cover meat, poultry, fish and seafood words and gelatin. `vegan` = `@vegetarian|@milk|@eggs|honey`, `gluten_free` = `@gluten`, `dairy_free` = `@milk`.
- [ ] **Step 2: Write `test_rules.py`:**
  - `allergens_in(["unsalted butter"]) == {"milk"}`;
  - `allergens_in(["butternut squash"]) == set()`;
  - `allergens_in(["peanut butter"]) == {"peanuts"}` (the milk exception, while peanuts still matches);
  - `allergens_in(["coconut milk"]) == set()`;
  - `allergens_in(["eggs"]) == {"eggs"}`;
  - `diet_flags(["chicken breast", "rice"])["vegetarian"] is False`;
  - `diet_flags(["honey", "oat"])` gives vegetarian True and vegan False;
  - `diet_flags(["coconut milk"])["dairy_free"] is True` (the exception passes through `@milk`);
  - `meal_types("Fluffy Pancakes") == ["breakfast"]`;
  - `meal_types("Chicken Soup") == ["lunch"]`;
  - `meal_types("Mystery Dish") == []`;
  - loading a file with an unknown `@nope` raises `ValueError`, and so does loading one with a duplicate key.
- [ ] **Step 3: Run** `python -m pytest tools/dataset/tests/test_rules.py -q`. Expected: FAIL.
- [ ] **Step 4: Implement `rules.py`:**
  - terms and exceptions are normalised with `normalise`;
  - titles are normalised with `normalise_words`;
  - matching follows Ruling 3;
  - a name that matches any of a row's exceptions is skipped for that row.
- [ ] **Step 5: Run the tests.** Expected: PASS.
- [ ] **Step 6: Commit** `feat(dataset): shared allergen, diet and meal rules`.

### Task 3: `prepare.py`

**Files:**
- Create: `tools/dataset/prepare.py`
- Create: `tools/dataset/tests/fixtures/mini_dataset.csv`: 20 rows in RecipeNLG's columns (`,title,ingredients,directions,link,source,NER`; the first column is the row id, and the list columns are JSON strings). It includes:
  - 1 row with invalid JSON;
  - 1 row with an empty NER;
  - 1 duplicate title;
  - 1 row with 2 ingredients (filtered out);
  - breakfast, lunch, dinner and untagged rows.
- Create: `tools/dataset/tests/test_prepare.py`

**Interfaces:**
- Consumes: `normalise`, `FoodRules`.
- Produces:
  - CLI `python tools/dataset/prepare.py [--input PATH] [--out DIR] [--min-common 50] [--per-meal 15000] [--untagged 5000]`. The defaults are `tools/dataset/data/full_dataset.csv` and `tools/dataset/out/`.
  - Functions, so the tests can call them directly:
    - `count_ingredients(rows) -> Counter[str]`;
    - `count_pairs(rows, common: set[str]) -> dict[str, list[tuple[str, int]]]` (top-N per ingredient, sorted by count descending and then name);
    - `select_recipes(rows, rules, per_meal, untagged, seed) -> list[RefRecipe]`;
    - `write_outputs(out_dir, recipes, stats, pairs)`.
  - `RefRecipe` is a dataclass: `id: int, title: str, ingredient_lines: list[str], ingredient_names: list[str], directions: list[str], meal_types: list[str], allergens: list[str], vegetarian/vegan/gluten_free/dairy_free: bool`.
  - Output files:
    - `reference_recipes.csv`, with columns in the V8 column order;
    - `ingredient_stats.csv`: `name,recipe_count`;
    - `ingredient_pairings.csv`: `ingredient,partner,together_count`.

    Arrays are written as Postgres array literals (`{"a","b"}`, with `"` and `\` escaped).

- [ ] **Step 1: Write the tests:**
  - `count_ingredients` on the fixture: the right count for `"egg"`, and the malformed row is skipped (a skipped counter equals 1, exposed as a returned or logged stat);
  - `count_pairs` with `top_n=2` keeps the two highest partners only, with ties broken by name;
  - `select_recipes`:
    - the same seed gives an identical id list twice;
    - no recipe has under 3 ingredient lines;
    - duplicate normalised titles are kept once;
    - with `per_meal=1, untagged=1`, a short bucket is filled from untagged;
  - `write_outputs` round-trips the escaping of a title containing `"` and `,`.
- [ ] **Step 2: Run** `python -m pytest tools/dataset/tests/test_prepare.py -q`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - **Streaming:** read with `csv.DictReader`, set `csv.field_size_limit` to the maximum, and reopen the file for each pass.
  - **Pair counting:** map common names to int ids and count `a * N + b` keys in a `Counter`, to keep memory down. `--min-common` is the escape hatch if memory runs short.
  - **Filters:** the spec §5.2 filters, plus the bucket order breakfast → lunch → dinner (a multi-tag recipe goes to the first bucket that still has room).
  - **Progress:** print a line every 100,000 rows, and at the end the totals per bucket, the number of rows skipped and the elapsed time.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(dataset): prepare reference recipes and pairing statistics`.

### Task 4: `load.py` and the user README

**Files:**
- Create: `tools/dataset/load.py`
- Create: `tools/dataset/README.md`: the user's step-by-step instructions:
  1. accept the terms at https://recipenlg.cs.put.poznan.pl/ (or a verified mirror; zip MD5 `3a168dfd0912bb034225619b3586ce76`, checked with `certutil -hashfile dataset.zip MD5`);
  2. unzip to `tools/dataset/data/full_dataset.csv`;
  3. `pip install -r tools/dataset/requirements.txt`;
  4. restart the backend so V8 is applied;
  5. `python tools/dataset/prepare.py`;
  6. set `SUPABASE_DB_PASSWORD` (the one-liner from the handoff);
  7. `python tools/dataset/load.py --dry-run`, then `python tools/dataset/load.py`.
- Create: `tools/dataset/tests/test_load.py`

**Interfaces:**
- Consumes: the CSV files from Task 3; V8's table names (Task 6).
- Produces:
  - CLI `python tools/dataset/load.py [--out DIR] [--dry-run]`;
  - `read_connection_settings(props_path: Path) -> dict` (host, port, dbname, user, parsed from `spring.datasource.url`/`username`);
  - `projected_bytes(current: int, csv_bytes: int) -> int` (`current + int(2.5 * csv_bytes)`);
  - `check_size(projected: int, limit: int = 400 * 1024 * 1024) -> None` (raises `SystemExit` with a message when over).

- [ ] **Step 1: Write the tests:**
  - `read_connection_settings` on a temporary properties file gives host `aws-0-eu-north-1.pooler.supabase.com`, port 5432, dbname `postgres`, and the user;
  - `check_size` raises over the limit and passes at it;
  - a missing `SUPABASE_DB_PASSWORD` exits non-zero with a message that doesn't contain any value;
  - `--dry-run` with a mocked connection executes no `INSERT` or `COPY`;
  - the source contains none of the strings `TRUNCATE` and `DELETE`.
- [ ] **Step 2: Run** `python -m pytest tools/dataset/tests/test_load.py -q`. Expected: FAIL.
- [ ] **Step 3: Implement:**
  1. check that all four tables exist in `information_schema.tables`; if not, exit with "Restart the backend so Flyway applies V8, then run again.";
  2. read `pg_database_size(current_database())`;
  3. run the size guard;
  4. in one transaction, for each table: `CREATE TEMP TABLE ... (LIKE <table>) ON COMMIT DROP`, then `COPY ... FROM STDIN (FORMAT csv, HEADER true)`, then `INSERT INTO <table> SELECT * FROM tmp ON CONFLICT DO NOTHING`;
  5. print the inserted and skipped counts and the size afterwards.

  `--dry-run` stops after the size guard.
- [ ] **Step 4: Run all the Python tests:** `python -m pytest tools/dataset/tests -q`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(dataset): insert-only loader with size guard`.

### Task 5: Java `FoodRules` and `IngredientNames`

**Files:**
- Create: `RecipeAppBackend/src/main/java/mk/ukim/finki/wp/recipeappbackend/food/IngredientNames.java`
- Create: `.../food/FoodRules.java` (`@Component`)
- Create: `.../food/Allergen.java` (`record Allergen(String key, String label)`)
- Test: `RecipeAppBackend/src/test/java/mk/ukim/finki/wp/recipeappbackend/food/IngredientNamesTest.java`, `.../food/FoodRulesTest.java`

**Interfaces:**
- Consumes: `food-rules.csv`, `ingredient-irregulars.csv` (classpath); `tools/dataset/normalise-cases.csv` (test only, at `Path.of("..", "tools", "dataset", "normalise-cases.csv")`, since Maven runs from `RecipeAppBackend/`).
- Produces:
  - `static Optional<String> IngredientNames.normalise(String raw)`;
  - `FoodRules`:
    - `List<Allergen> allergens()`;
    - `Set<String> allergenKeys()`;
    - `Set<String> dietKeys()`;
    - `Set<String> allergensIn(Collection<String> names)`;
    - `Set<String> dietViolations(Collection<String> names, Set<String> diets)` (the diets the names break).

- [ ] **Step 1: Write `IngredientNamesTest`,** a `@ParameterizedTest` over every row of the shared cases file (an empty `expected` means `Optional.empty()`).
- [ ] **Step 2: Write `FoodRulesTest`:**
  - `allergens()` has 14 entries, the first `("gluten", "Cereals containing gluten")`;
  - `dietKeys()` is the 4 diet keys;
  - the same allergen cases as Task 2 Step 2: unsalted butter, butternut squash, peanut butter, coconut milk, eggs;
  - `dietViolations(List.of("honey"), Set.of("vegan", "vegetarian")) == Set.of("vegan")`;
  - a constructor taking a `Reader` fails fast with `IllegalStateException` on an unknown `@key`.
- [ ] **Step 3: Run the spoonacular check, then** `./mvnw -q test -Dtest=IngredientNamesTest,FoodRulesTest`. Expected: FAIL (compilation).
- [ ] **Step 4: Implement.**
  - **`IngredientNames`:** mirror the Python steps exactly (`Normalizer.Form.NFKD`, remove `\p{M}`, `toLowerCase(Locale.ROOT)`). Irregulars are loaded once, statically, from the classpath.
  - **`FoodRules`:** parse with a small CSV reader (the fields have no quotes; split on `,` with a limit of 5), expand `@key` recursively, and normalise terms through `IngredientNames`.
- [ ] **Step 5: Run the tests.** Expected: PASS.
- [ ] **Step 6: Commit** `feat(backend): shared food rules and ingredient names`.

### Task 6: V8 migration and the preferences API

**Files:**
- Create: `RecipeAppBackend/src/main/resources/db/migration/V8__food_preferences_and_reference_data.sql` (exactly the spec §3 SQL)
- Create: `.../model/entities/UserPreferences.java` (`@Id UUID userId`; five `List<String>` fields mapped like `Recipe.cuisines`; `@CreationTimestamp`/`@UpdateTimestamp` `OffsetDateTime`)
- Create: `.../repository/UserPreferencesRepository.java` (`JpaRepository<UserPreferences, UUID>`)
- Create: `.../model/displayDTO/PreferencesDto.java`: `record PreferencesDto(List<String> allergens, List<String> diets, List<String> dislikedIngredients, List<String> likedIngredients, List<String> likedCuisines)`, used for both request and response (null lists are treated as empty)
- Create: `.../exception/InvalidPreferencesException.java`; handler in `GlobalExceptionHandler` → 400 ProblemDetail with the message as `detail`
- Create: `.../service/PreferencesService.java`, `.../controller/PreferencesController.java`
- Modify:
  - `MyProfileDto` (add `boolean hasPreferences`; `of(User, long, boolean)`);
  - `UserService` (inject `UserPreferencesRepository`; `toMyProfile` uses `existsById`).
- Test:
  - `.../service/PreferencesServiceTest.java`;
  - `.../controller/PreferencesControllerTest.java`;
  - `UserControllerTest` (fixture updates plus a `hasPreferences` assertion);
  - `.../repository/FoodSchemaDbTest.java`.

**Interfaces:**
- Consumes: `FoodRules.allergenKeys()`, `dietKeys()`, `IngredientNames.normalise`, `CuisineCatalog.normalizeAndValidate(List<String>)`.
- Produces:
  - `PreferencesService.get(UUID userId) -> PreferencesDto` (throws `ResourceNotFoundException`);
  - `PreferencesService.save(UUID userId, PreferencesDto dto) -> PreferencesDto`;
  - `GET`/`PUT /api/users/me/preferences`;
  - JSON field `hasPreferences` on `/api/users/me`.

- [ ] **Step 1: Write `PreferencesServiceTest`** (mocked repository):
  - `get` with no row throws `ResourceNotFoundException`;
  - `save` normalises and de-duplicates: `["Mushrooms", "mushroom", "  "]` → `["mushroom"]`;
  - an unknown allergen `"nuts"` gives `InvalidPreferencesException` with the message `Unknown allergen: nuts`;
  - an unknown diet gives `Unknown diet: keto`;
  - 31 dislikes give `At most 30 disliked ingredients`;
  - 21 cuisines give `At most 20 cuisines`;
  - an unknown cuisine passes through `InvalidCuisineException`;
  - saving twice for the same user updates the one entity (`findById` returns the existing one, and a single `save` per call is verified);
  - allergens and diets are stored de-duplicated, in the request's order.
- [ ] **Step 2: Write `PreferencesControllerTest`,** in the `UserControllerTest` style (`@WebMvcTest`, `@Import(SecurityConfig.class)`, JWT post-processor):
  - GET returns 404 when the service throws;
  - GET returns 200 with the JSON fields;
  - PUT returns 200 and passes the DTO through;
  - PUT returns 400 with `detail` on `InvalidPreferencesException`;
  - an unauthenticated GET returns 401.

  Also update `UserControllerTest` for the new `MyProfileDto` constructor, and assert `$.hasPreferences`.
- [ ] **Step 3: Write `FoodSchemaDbTest`:** with the DbTest annotations, assert through `JdbcTemplate` that `information_schema.tables` holds all four V8 tables.
- [ ] **Step 4: Run the spoonacular check, then** `./mvnw -q test -Dtest=PreferencesServiceTest,PreferencesControllerTest,UserControllerTest`. Expected: FAIL.
- [ ] **Step 5: Implement** the migration, entity, repository, DTO, exception and handler, service, controller and `MyProfileDto`/`UserService` changes. The service is `@Transactional`.
- [ ] **Step 6: Run the same three classes.** Expected: PASS. Also run the full regression list from the handoff (the 39 existing classes plus the new ones). Expected: PASS.
- [ ] **Step 7: Commit** `feat(backend): food preferences API and V8 schema` and push.
- [ ] **Step 8: USER CHECKPOINT.**
  - **Ask the user** to restart the backend in IntelliJ so Flyway applies V8. Hibernate's `ddl-auto=validate` means every DbTest fails until this happens.
  - **After they confirm:** export the password, then run `FoodSchemaDbTest` plus all 46 existing DbTests. Expected: PASS.

### Task 7: Public food lookups and the reference query

**Files:**
- Create: `.../model/projection/ReferenceRecipe.java`: `record ReferenceRecipe(long id, String title, List<String> ingredientLines, List<String> ingredientNames, List<String> directions, List<String> mealTypes, int overlap)`
- Create: `.../model/projection/ReferenceQuery.java`: `record ReferenceQuery(Set<String> wanted, String mealType /* nullable */, Set<String> allergens, Set<String> diets, Set<String> dislikes, int limit)`
- Create: `.../repository/ReferenceDataRepository.java` (`@Repository`, `NamedParameterJdbcTemplate`)
- Create: `.../controller/FoodController.java`
- Modify: `SecurityConfig` (`permitAll` for GET `/api/food/allergens` and `/api/ingredients/suggest`)
- Test:
  - `.../controller/FoodControllerTest.java`;
  - `.../repository/ReferenceRecipeQueryDbTest.java`;
  - `.../repository/IngredientSuggestDbTest.java` (both DbTests `@Import(ReferenceDataRepository.class)`).

**Interfaces:**
- Consumes: `FoodRules.allergens()`, `IngredientNames.normalise`.
- Produces:
  - `ReferenceDataRepository.suggestIngredients(String normalisedPrefix, int limit) -> List<String>`;
  - `ReferenceDataRepository.findClosest(ReferenceQuery q) -> List<ReferenceRecipe>`;
  - `GET /api/food/allergens` → `[{key,label}]`;
  - `GET /api/ingredients/suggest?q=` → `string[]` (at most 10).

- [ ] **Step 1: Write `FoodControllerTest`:**
  - allergens returns 14 items without auth;
  - suggest with `q=Gar` calls the repository with `"gar"` and 10;
  - suggest with `q=%`, `q=_`, `q=a` or an empty `q` returns `[]` and never calls the repository.
- [ ] **Step 2: Write `IngredientSuggestDbTest`.** Insert `garlic` (900), `garlic powder` (300) and `garam masala` (50) into `ingredient_stats`, then:
  - `suggestIngredients("gar", 10)` starts with `["garlic", "garlic powder", "garam masala"]` in that relative order;
  - `suggestIngredients("garl", 1)` has size 1.
- [ ] **Step 3: Write `ReferenceRecipeQueryDbTest`.** Insert reference rows with ids -1 to -5:
  - -1: names `[egg, flour, milk]`, breakfast, allergens `{eggs, gluten, milk}`;
  - -2: `[egg, spinach]`, breakfast, `{eggs}`, vegetarian;
  - -3: `[shiitake mushroom, egg, rice]`, dinner, `{eggs}`;
  - -4: `[chicken, rice]`, dinner, `{}`;
  - -5: `[oat, banana, honey]`, breakfast, `{}`, vegetarian, not vegan.

  Then:
  - wanted `{egg, spinach}` gives -2 first (overlap 2);
  - mealType `dinner` with wanted `{egg, rice}` gives only -3 and -4;
  - allergens `{milk}` excludes -1;
  - diet `vegan` with wanted `{banana}` gives nothing;
  - dislike `mushroom` with wanted `{egg}` excludes -3;
  - an empty `wanted` returns `[]`;
  - the `limit` is respected.
- [ ] **Step 4: Run the spoonacular check, then** `./mvnw -q test -Dtest=FoodControllerTest,IngredientSuggestDbTest,ReferenceRecipeQueryDbTest` (password exported). Expected: FAIL.
- [ ] **Step 5: Implement.**
  - **Suggest:** `SELECT name FROM ingredient_stats WHERE name LIKE :p ESCAPE '\' ORDER BY recipe_count DESC, name LIMIT :n`, with the prefix LIKE-escaped. The controller returns `[]` when the normalised `q` is empty or shorter than 2 characters.
  - **`findClosest`:**
    - build SQL with the overlap expression `cardinality(ARRAY(SELECT unnest(ingredient_names) INTERSECT SELECT unnest(:wanted)))`;
    - candidate filter `ingredient_names && :wanted`;
    - an optional `:mealType = ANY(meal_types)`;
    - `NOT (allergens && :allergens)`;
    - one `AND <column>` per diet, from a fixed key→column map;
    - the dislike `NOT EXISTS` from Ruling 7;
    - `ORDER BY overlap DESC, cardinality(ingredient_names), id LIMIT :limit`.

    Bind arrays as `text[]`, through `SqlParameterValue(Types.ARRAY, ...)` or `connection.createArrayOf`; the DbTest proves the binding works.
- [ ] **Step 6: Run the tests.** Expected: PASS.
- [ ] **Step 7: Commit** `feat(backend): ingredient suggestions and reference recipe query` and push.

### Task 8: Frontend sign-in state, API and types

**Files:**
- Modify: `recipe-app-frontend/src/lib/types.ts`:
  - `MyProfile.hasPreferences: boolean`;
  - `export type Diet = 'vegetarian' | 'vegan' | 'gluten_free' | 'dairy_free'`;
  - `export interface Preferences { allergens: string[]; diets: Diet[]; dislikedIngredients: string[]; likedIngredients: string[]; likedCuisines: string[] }`;
  - `export interface Allergen { key: string; label: string }`.
- Create:
  - `src/api/preferences.ts` (`getPreferences(): Promise<Preferences>`, `savePreferences(p: Preferences): Promise<Preferences>`, `preferencesQueryKey = (userId: string) => ['preferences', userId]`);
  - `src/api/food.ts` (`getAllergens(): Promise<Allergen[]>`, `allergensQueryKey = ['allergens']`, `suggestIngredients(q: string): Promise<string[]>`).
- Modify:
  - `src/auth/AuthProvider.tsx` (`Status` adds `'needs-preferences'`; both the `getMe` success path and `setProfile` set `me.hasPreferences ? 'ready' : 'needs-preferences'`);
  - `src/auth/ProtectedRoute.tsx`;
  - `src/test/fixtures.ts` (`profileFixture.hasPreferences = true`);
  - `CompleteProfilePage.tsx` (navigates to `/preferences/setup` after `setProfile`).
- Create: `src/auth/OnboardingRedirect.tsx`, rendered in `AppShell` (Ruling 10).
- Test: `src/auth/onboarding.test.tsx`

**Interfaces:**
- Produces: `useAuth().status` can be `'needs-preferences'`, with `profile` set; the API functions and query keys above.

- [ ] **Step 1: Write `onboarding.test.tsx`** (with the `renderApp` and supabase mock pattern of `profileTabs.test.tsx`), using `/api/users/me` with `hasPreferences: false`:
  - visiting `/` lands on `/preferences/setup`;
  - visiting `/me` lands on `/preferences/setup`;
  - visiting `/privacy` stays on `/privacy`;
  - with `hasPreferences: true`, `/` stays on `/`.

  For the last two cases, only assert the path. The setup page's own tests are in Task 9; until that task, a placeholder route element is enough.
- [ ] **Step 2: Run** `npx vitest run src/auth/onboarding.test.tsx`. Expected: FAIL.
- [ ] **Step 3: Implement** the files above. Add the route `/preferences/setup` → `<ProtectedRoute><PreferencesSetupPage /></ProtectedRoute>`, using a minimal stub page to be replaced in Task 9.
- [ ] **Step 4: Run** `npx vitest run` and `npm run build`. Expected: all green. The existing tests stay green thanks to the fixture's `hasPreferences: true`.
- [ ] **Step 5: Commit** `feat(frontend): needs-preferences sign-in state` and push.

### Task 9: Preferences form and the setup page

**Files:**
- Create:
  - `src/features/preferences/IngredientChipInput.tsx`;
  - `src/features/preferences/PreferencesForm.tsx`;
  - `src/features/preferences/PreferencesSetupPage.tsx` (replaces the stub).
- Modify: `src/features/authoring/CuisinePicker.tsx` (an optional `max?: number` prop, default `MAX_CUISINES`).
- Test: `src/features/preferences/preferences.test.tsx`

**Interfaces:**
- Consumes: the Task 8 API, types and `useAuth().setProfile`.
- Produces:
  - `IngredientChipInput({ label, values, onChange, max }: { label: string; values: string[]; onChange: (v: string[]) => void; max: number })`;
  - `PreferencesForm({ initial, submitLabel, onSave }: { initial?: Preferences; submitLabel: string; onSave: (p: Preferences) => Promise<void> })`.

**Copy (exact):**
- Setup title: "Your food preferences". Subtitle: "We use these to suggest recipes. Allergies and ingredients you avoid are always filtered out."
- The allergies legend is "Food allergies"; the opt-out option is "I have no food allergies". Error: "Choose your allergies, or tick I have no food allergies."
- The diet legend is "Diet", with options "Vegetarian", "Vegan", "Gluten-free", "Dairy-free".
- Chip-input labels: "Ingredients I don't eat" and "Favourite ingredients". The likes legend is "What I like".
- Button: "Save preferences".

- [ ] **Step 1: Write `preferences.test.tsx`** (MSW for `/api/food/allergens`, `/api/ingredients/suggest`, `PUT /api/users/me/preferences`, `/api/users/me`):
  - submitting with nothing ticked shows the allergy error on the group (`aria-describedby` on the fieldset) and sends no PUT;
  - ticking "Milk" then "I have no food allergies" unticks Milk, and vice versa;
  - typing `gar` shows suggestions after the debounce, and clicking `garlic` adds a chip;
  - typing `okra` and pressing Enter adds a chip, and so does a comma;
  - duplicates (`Okra` after `okra`) are ignored;
  - the remove button removes a chip;
  - at 30 chips the input is disabled;
  - a valid submit sends a PUT body with `allergens: []` (for no allergies), `diets: ['vegan']` and the chips, then lands on `/`;
  - a failed PUT with a `detail` shows it in a `FormError`;
  - `expectNoEmoji()`.
- [ ] **Step 2: Run** `npx vitest run src/features/preferences`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - **Suggestions:** reuse `SearchBar`'s 300 ms debounce pattern, and `useQuery(['ingredientSuggest', q], ...)` enabled when `q.trim().length >= 2`. A failed suggest renders no list.
  - **Chips:** trimmed, at most 60 characters, compared case-insensitively.
  - **Initial state:** given an `initial` with `allergens: []`, "I have no food allergies" starts ticked.
  - **On save:** the setup page calls `savePreferences`, then `setProfile({ ...profile, hasPreferences: true })`, then `navigate('/', { replace: true })`.
- [ ] **Step 4: Run** `npx vitest run` and `npm run build`. Expected: green.
- [ ] **Step 5: Commit** `feat(frontend): food preferences setup step` and push.

### Task 10: Profile Preferences tab and Privacy Policy

**Files:**
- Modify:
  - `src/features/profile/ProfileTabs.tsx` (`TabKey` adds `'preferences'`, label "Preferences", `?tab=preferences`);
  - `src/features/profile/MyProfilePage.tsx`;
  - `src/features/content/PrivacyPage.tsx`.
- Create: `src/features/preferences/PreferencesPanel.tsx`
- Test: `src/features/profile/profileTabs.test.tsx` (extend), `src/features/preferences/preferencesPanel.test.tsx`

**Interfaces:**
- Consumes: `PreferencesForm`, `getPreferences`, `savePreferences`, `preferencesQueryKey`.

**Privacy copy (exact, a new paragraph under the data-we-collect section):**
"Food preferences. When you sign up we ask about food allergies, your diet, and ingredients you like or avoid. Allergy and diet details can say something about your health, so we use them only to filter and personalise recipe suggestions for you. They are never shown to other people. You can change them at any time on your profile, and they are deleted together with your account."

- [ ] **Step 1: Write the tests:**
  - **`profileTabs`:** three tabs; arrow keys cycle mine → saved → preferences → mine; `?tab=preferences` selects it.
  - **`preferencesPanel`:**
    - loads saved values (a ticked allergen, chips, cuisines);
    - with `allergens: []`, "I have no food allergies" is ticked;
    - saving sends a PUT and shows the toast "Preferences saved";
    - a 404 from GET shows the empty form.
  - **Privacy:** the page contains "Food preferences." and passes `expectNoEmoji()`.
- [ ] **Step 2: Run** `npx vitest run src/features/profile src/features/preferences src/features/content`. Expected: FAIL.
- [ ] **Step 3: Implement.** `PreferencesPanel` uses `useQuery(preferencesQueryKey(userId), getPreferences)`; after a save it writes the response with `setQueryData`.
- [ ] **Step 4: Run** `npx vitest run` and `npm run build`. Expected: green.
- [ ] **Step 5: Commit** `feat(frontend): preferences tab and privacy note` and push.

### Task 11: Dev log

**Files:**
- Modify: `docs/plateful-frontend-dev-log.txt`: a new `PART 17 — FOOD PREFERENCES AND THE RECIPE DATASET`. It uses the existing banner style, wraps at 80 columns, and uses CRLF line endings. It covers:
  - what the user sees;
  - the dataset pipeline and why it is behind the scenes (the licence);
  - the shared rules files;
  - the test totals;
  - the user's manual steps (download, prepare and load, delete old users, re-set admin and 2FA).

- [ ] **Step 1: Write PART 17.** Check that no line is over 80 columns and that line endings are CRLF (`file` / a python check).
- [ ] **Step 2: Commit** `docs: dev log for food preferences and the dataset` and push.

### After the last task (user, with support)

1. Download RecipeNLG (`tools/dataset/README.md`).
2. Run `prepare.py`, then `load.py --dry-run`, then `load.py`. Report the sizes.
3. Delete the old users in Supabase Authentication → Users. Sign up through the new flow, then set `is_admin` and enrol 2FA.
4. Whole-branch review on the strongest model. Then offer: merge to main locally, push, and delete the branch.
