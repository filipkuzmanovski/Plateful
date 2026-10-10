# Golden Hour Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle Plateful in the Golden Hour look (no emojis), replace the inline filter bar with a slide-in filter drawer backed by a curated, server-validated cuisine catalogue (multi-cuisine and region filtering, A–Z view), and rebalance the seeded data to about 10 recipes per Spoonacular cuisine.

**Architecture:**
- **Backend owns the catalogue.** A classpath CSV is loaded by a `CuisineCatalog` component. It drives validation on save, region expansion in browse filtering, the importer's tag filter, and a public `GET /api/recipes/cuisines` endpoint with live counts.
- **The frontend reads the catalogue from that endpoint.** It restyles every page through new theme tokens and shared atoms. Filter state stays in the URL; the drawer edits a local draft and applies it to the URL.

**Tech Stack:**
- Backend: Spring Boot 4.1 / Hibernate 7.4 / Postgres (Supabase).
- Frontend: React 19, Vite, Tailwind v4, TanStack Query, Radix Dialog, `lucide-react`, Fontsource (Fraunces + Inter), Vitest + RTL + MSW.

**Spec:** `docs/superpowers/specs/2026-10-10-golden-hour-redesign-design.md`

## Global Constraints

**Design:**
- Tokens: `cream #FBF6EE`, `paper #FFFDF8`, `olive #2F3A2B`, `saffron #E8A33D`, `terracotta #C65D3B`, `sand #EADFCD`, `muted #8A8A7A`.
- Fraunces (headings and the wordmark "Plateful.") plus Inter (body), self-hosted via Fontsource.
- **No emoji characters anywhere in the UI.** Use `lucide-react` icons where an icon helps.
- Visual reference mockups (local, gitignored): `.superpowers/brainstorm/1303-1791653389/content/visual-direction.html` (option A), `filter-panel.html` (option A), `cuisine-az.html`.

**Cuisine catalogue:**
- Single source of truth: `RecipeAppBackend/src/main/resources/cuisines.csv`. Keys are lowercase; region is one of `europe`, `asia`, `americas`, `africa`, `middle_east`, `oceania`; the region key `mediterranean` means the `mediterranean = true` entries.
- **Filtering:** URL params `cuisine` and `region` are repeatable; matches are any-of (union); a single `?cuisine=x` still works. Unknown values in a filter are ignored, and if every requested value is unknown the result is **empty, not unfiltered**.
- **Saving:** cuisines are normalized (trim, lowercase, collapse whitespace, dedupe), and values not in the catalogue get a 400 ProblemDetail field error on `cuisines`. Max 10 entries, ≤50 characters each.

**Drawer:**
- Regions: Mediterranean, Europe, Asia, Americas, Africa, Middle East, Oceania. Then the top 10 popular cuisines (count > 0), then an A–Z view (count > 0, search, letter index) inside the same drawer.
- Dish type as text pills.
- Max time: number input 1–600 with quick picks 15/30/60. Min rating: number input 1–5, step 0.5. Invalid input → inline hint, field excluded.
- Draft → "Show N recipes" (debounced count via browse `size=1`). Removable active chips above the grid.

**Importer:**
- `spoonacular.import.replace-existing` (default false) deletes only `source='spoonacular'` recipes, in one transaction, before importing.
- Defaults: 27 Spoonacular cuisines, `recipes-per-cuisine=10`.
- The real replace run happens **only after explicit user confirmation**.

**Commands:**
- Backend (Bash, from `RecipeAppBackend/`, after `export JAVA_HOME="$USERPROFILE/.jdks/ms-21.0.9"`): always the filtered form `./mvnw test "-Dtest=..."`. **Never the unfiltered suite.**
- Frontend (Bash, from `recipe-app-frontend/`): `npx vitest run` and `npm run build`. Ignore the pre-existing >500 kB chunk warning.

**Repo rules:**
- Work on branch `feature/golden-hour`. Commit per task with the session's Co-Authored-By trailer.
- **Never stage files outside your task.** The working tree holds the user's own uncommitted edits in `RecipeAppBackend/.../config/SecurityConfig.java`, `WebConfig.java`, `RecipeRating.java`, `User.java`, `CurrentUserId.java`, `CurrentUserIdArgumentResolver.java`, and `application.properties`. If a task must edit one of those files, stage only your own hunks with `git add -p`.
- After each task, append a section to `docs/plateful-frontend-dev-log.txt`: what, how, code explained, tests.

## Review Focus

1. **Only unknown values requested** (e.g. `?cuisine=lol` or `?region=atlantis`) must return **zero** recipes, never all of them. Tested in Task 1 (catalogue resolve) and Task 2 (controller → service).
2. **`/api/recipes/cuisines` routing:** it must not be swallowed by `/api/recipes/{id}`, and it must stay public for anonymous visitors. Tested in Task 3.
3. **Closing the drawer without applying** (✕, Escape, or clicking outside) leaves the URL unchanged, and reopening shows the applied filters, not the abandoned draft. Tested in Task 10.
4. **Messy cuisine input on save:** `"  Italian "` and `"ITALIAN"` collapse to one `italian`. A list that is still more than 10 after dedupe is rejected. An unknown value gives a 400 naming it. Tested in Task 4.
5. **Region plus explicit cuisine together** is a union (`region=asia&cuisine=italian` returns Asian and Italian recipes) and is ANDed with the diet, time and rating filters. Tested in Task 2 (resolve) and Task 5 (real DB).

---

### Task 1: Cuisine catalogue (backend)

**Files:**
- Create: `RecipeAppBackend/src/main/resources/cuisines.csv` (content below)
- Create: `.../recipeappbackend/cuisine/Region.java`, `.../cuisine/CuisineEntry.java`, `.../cuisine/CuisineCatalog.java`, `.../cuisine/InvalidCuisineException.java`
- Test: `.../test/java/.../cuisine/CuisineCatalogTest.java`

**Interfaces (Produces):**
- `enum Region { EUROPE, ASIA, AMERICAS, AFRICA, MIDDLE_EAST, OCEANIA }` with `String key()`, the lowercase name (`"middle_east"`), and `static Optional<Region> fromKey(String)`.
- `record CuisineEntry(String key, String label, Region region, boolean mediterranean)`.
- `@Component class CuisineCatalog`. It has a public no-arg constructor that loads `/cuisines.csv` from the classpath, so tests and the translator can `new` it. It also has:
  - `List<CuisineEntry> all()`
  - `Optional<CuisineEntry> find(String key)`
  - `boolean contains(String key)`
  - `static String normalize(String raw)`: trim, lowercase (`Locale.ROOT`), collapse internal whitespace to a single space; null maps to `""`.
  - `Set<String> keysForRegion(String regionKey)`: `"mediterranean"` gives the entries with `mediterranean = true`; any other region key gives that region's entries; unknown gives an empty set.
  - `Optional<Set<String>> resolveFilter(List<String> cuisines, List<String> regions)`. Returns `Optional.empty()` when both lists are null or blank, meaning "no cuisine filter". Otherwise returns `Optional.of(union)`, which may be empty, meaning "match nothing". Cuisines are normalized and kept only if in the catalogue; regions are expanded.
  - `List<String> normalizeAndValidate(List<String> raw)`. Null gives an empty list. It normalizes, drops blanks, dedupes preserving first-seen order, and throws `InvalidCuisineException(List<String> unknown)` if any value is not in the catalogue.
- `class InvalidCuisineException extends RuntimeException` with `List<String> unknown()`. Message: `"Unknown cuisine: " + String.join(", ", unknown)`.

- [ ] **Step 1: Create `cuisines.csv`** with a header `key,label,region,mediterranean`, then exactly these rows:

```csv
key,label,region,mediterranean
european,European,europe,false
eastern european,Eastern European,europe,false
nordic,Nordic,europe,false
mediterranean,Mediterranean,europe,true
albanian,Albanian,europe,true
andorran,Andorran,europe,false
austrian,Austrian,europe,false
belarusian,Belarusian,europe,false
belgian,Belgian,europe,false
bosnian,Bosnian,europe,true
british,British,europe,false
bulgarian,Bulgarian,europe,false
croatian,Croatian,europe,true
cypriot,Cypriot,europe,true
czech,Czech,europe,false
danish,Danish,europe,false
dutch,Dutch,europe,false
estonian,Estonian,europe,false
finnish,Finnish,europe,false
french,French,europe,true
german,German,europe,false
greek,Greek,europe,true
hungarian,Hungarian,europe,false
icelandic,Icelandic,europe,false
irish,Irish,europe,false
italian,Italian,europe,true
kosovar,Kosovar,europe,false
latvian,Latvian,europe,false
liechtensteiner,Liechtensteiner,europe,false
lithuanian,Lithuanian,europe,false
luxembourgish,Luxembourgish,europe,false
macedonian,Macedonian,europe,false
maltese,Maltese,europe,true
moldovan,Moldovan,europe,false
monegasque,Monegasque,europe,true
montenegrin,Montenegrin,europe,true
norwegian,Norwegian,europe,false
polish,Polish,europe,false
portuguese,Portuguese,europe,false
romanian,Romanian,europe,false
russian,Russian,europe,false
sammarinese,Sammarinese,europe,false
serbian,Serbian,europe,false
slovak,Slovak,europe,false
slovenian,Slovenian,europe,true
spanish,Spanish,europe,true
swedish,Swedish,europe,false
swiss,Swiss,europe,false
ukrainian,Ukrainian,europe,false
asian,Asian,asia,false
afghan,Afghan,asia,false
armenian,Armenian,asia,false
azerbaijani,Azerbaijani,asia,false
bangladeshi,Bangladeshi,asia,false
bhutanese,Bhutanese,asia,false
bruneian,Bruneian,asia,false
burmese,Burmese,asia,false
cambodian,Cambodian,asia,false
chinese,Chinese,asia,false
filipino,Filipino,asia,false
georgian,Georgian,asia,false
indian,Indian,asia,false
indonesian,Indonesian,asia,false
japanese,Japanese,asia,false
kazakh,Kazakh,asia,false
korean,Korean,asia,false
kyrgyz,Kyrgyz,asia,false
lao,Lao,asia,false
malaysian,Malaysian,asia,false
maldivian,Maldivian,asia,false
mongolian,Mongolian,asia,false
nepali,Nepali,asia,false
pakistani,Pakistani,asia,false
singaporean,Singaporean,asia,false
sri lankan,Sri Lankan,asia,false
taiwanese,Taiwanese,asia,false
tajik,Tajik,asia,false
thai,Thai,asia,false
timorese,Timorese,asia,false
turkmen,Turkmen,asia,false
uzbek,Uzbek,asia,false
vietnamese,Vietnamese,asia,false
middle eastern,Middle Eastern,middle_east,false
jewish,Jewish,middle_east,false
bahraini,Bahraini,middle_east,false
emirati,Emirati,middle_east,false
iranian,Iranian,middle_east,false
iraqi,Iraqi,middle_east,false
israeli,Israeli,middle_east,true
jordanian,Jordanian,middle_east,false
kuwaiti,Kuwaiti,middle_east,false
lebanese,Lebanese,middle_east,true
omani,Omani,middle_east,false
palestinian,Palestinian,middle_east,true
qatari,Qatari,middle_east,false
saudi,Saudi,middle_east,false
syrian,Syrian,middle_east,true
turkish,Turkish,middle_east,true
yemeni,Yemeni,middle_east,false
african,African,africa,false
algerian,Algerian,africa,true
angolan,Angolan,africa,false
beninese,Beninese,africa,false
bissau-guinean,Bissau-Guinean,africa,false
botswanan,Botswanan,africa,false
burkinabe,Burkinabe,africa,false
burundian,Burundian,africa,false
cameroonian,Cameroonian,africa,false
cape verdean,Cape Verdean,africa,false
central african,Central African,africa,false
chadian,Chadian,africa,false
comorian,Comorian,africa,false
congolese,Congolese,africa,false
djiboutian,Djiboutian,africa,false
egyptian,Egyptian,africa,true
equatorial guinean,Equatorial Guinean,africa,false
eritrean,Eritrean,africa,false
ethiopian,Ethiopian,africa,false
gabonese,Gabonese,africa,false
gambian,Gambian,africa,false
ghanaian,Ghanaian,africa,false
guinean,Guinean,africa,false
ivorian,Ivorian,africa,false
kenyan,Kenyan,africa,false
basotho,Basotho,africa,false
liberian,Liberian,africa,false
libyan,Libyan,africa,true
malagasy,Malagasy,africa,false
malawian,Malawian,africa,false
malian,Malian,africa,false
mauritanian,Mauritanian,africa,false
mauritian,Mauritian,africa,false
moroccan,Moroccan,africa,true
mozambican,Mozambican,africa,false
namibian,Namibian,africa,false
nigerien,Nigerien,africa,false
nigerian,Nigerian,africa,false
rwandan,Rwandan,africa,false
sao tomean,Sao Tomean,africa,false
senegalese,Senegalese,africa,false
seychellois,Seychellois,africa,false
sierra leonean,Sierra Leonean,africa,false
somali,Somali,africa,false
south african,South African,africa,false
south sudanese,South Sudanese,africa,false
sudanese,Sudanese,africa,false
swazi,Swazi,africa,false
tanzanian,Tanzanian,africa,false
togolese,Togolese,africa,false
tunisian,Tunisian,africa,true
ugandan,Ugandan,africa,false
zambian,Zambian,africa,false
zimbabwean,Zimbabwean,africa,false
latin american,Latin American,americas,false
caribbean,Caribbean,americas,false
cajun,Cajun,americas,false
southern,Southern,americas,false
american,American,americas,false
antiguan,Antiguan,americas,false
argentine,Argentine,americas,false
bahamian,Bahamian,americas,false
barbadian,Barbadian,americas,false
belizean,Belizean,americas,false
bolivian,Bolivian,americas,false
brazilian,Brazilian,americas,false
canadian,Canadian,americas,false
chilean,Chilean,americas,false
colombian,Colombian,americas,false
costa rican,Costa Rican,americas,false
cuban,Cuban,americas,false
dominican,Dominican,americas,false
ecuadorian,Ecuadorian,americas,false
grenadian,Grenadian,americas,false
guatemalan,Guatemalan,americas,false
guyanese,Guyanese,americas,false
haitian,Haitian,americas,false
honduran,Honduran,americas,false
jamaican,Jamaican,americas,false
mexican,Mexican,americas,false
nicaraguan,Nicaraguan,americas,false
panamanian,Panamanian,americas,false
paraguayan,Paraguayan,americas,false
peruvian,Peruvian,americas,false
puerto rican,Puerto Rican,americas,false
salvadoran,Salvadoran,americas,false
surinamese,Surinamese,americas,false
trinidadian,Trinidadian,americas,false
uruguayan,Uruguayan,americas,false
venezuelan,Venezuelan,americas,false
australian,Australian,oceania,false
fijian,Fijian,oceania,false
hawaiian,Hawaiian,oceania,false
new zealand,New Zealand,oceania,false
papua new guinean,Papua New Guinean,oceania,false
samoan,Samoan,oceania,false
solomon islander,Solomon Islander,oceania,false
tongan,Tongan,oceania,false
vanuatuan,Vanuatuan,oceania,false
```

- [ ] **Step 2: Write `CuisineCatalogTest`** (plain JUnit, `new CuisineCatalog()`):

```java
@Test void loadsEveryRowWithUniqueKeys() { // ≥ 190 entries, keys unique, all already normalized
  assertThat(catalog.all()).hasSizeGreaterThanOrEqualTo(190);
  assertThat(catalog.all()).extracting(CuisineEntry::key).doesNotHaveDuplicates()
      .allMatch(k -> k.equals(CuisineCatalog.normalize(k)));
}
@Test void containsAllSpoonacularCuisines() {
  List.of("african","american","asian","british","cajun","caribbean","chinese","eastern european","european",
      "french","german","greek","indian","irish","italian","japanese","jewish","korean","latin american",
      "mediterranean","mexican","middle eastern","nordic","southern","spanish","thai","vietnamese")
    .forEach(k -> assertThat(catalog.contains(k)).as(k).isTrue());
}
@Test void macedonianIsEuropean() { assertThat(catalog.find("macedonian")).get()
    .extracting(CuisineEntry::label, CuisineEntry::region).containsExactly("Macedonian", Region.EUROPE); }
@Test void mediterraneanRegionSpansContinents() { assertThat(catalog.keysForRegion("mediterranean"))
    .contains("mediterranean", "italian", "lebanese", "moroccan").doesNotContain("german"); }
@Test void unknownRegionIsEmpty() { assertThat(catalog.keysForRegion("atlantis")).isEmpty(); }
@Test void normalizeTrimsLowercasesAndCollapses() {
  assertThat(CuisineCatalog.normalize("  Middle   Eastern ")).isEqualTo("middle eastern");
  assertThat(CuisineCatalog.normalize(null)).isEqualTo(""); }
@Test void resolveFilterNoInputMeansNoFilter() {
  assertThat(catalog.resolveFilter(null, List.of())).isEmpty(); }
@Test void resolveFilterUnionsRegionsAndCuisines() {
  Set<String> keys = catalog.resolveFilter(List.of(" Italian "), List.of("asia")).orElseThrow();
  assertThat(keys).contains("italian", "chinese", "japanese", "asian").doesNotContain("mexican"); }
@Test void resolveFilterOnlyUnknownMeansMatchNothing() {
  assertThat(catalog.resolveFilter(List.of("lol"), List.of("atlantis"))).hasValue(Set.of()); }
@Test void normalizeAndValidateDedupesAndRejectsUnknown() {
  assertThat(catalog.normalizeAndValidate(List.of("  Italian ", "ITALIAN", "Greek"))).containsExactly("italian", "greek");
  assertThatThrownBy(() -> catalog.normalizeAndValidate(List.of("italian", "lol")))
      .isInstanceOf(InvalidCuisineException.class).hasMessageContaining("lol"); }
```

- [ ] **Step 3: Run it.** `./mvnw test "-Dtest=CuisineCatalogTest"` should fail to compile (missing classes).
- [ ] **Step 4: Implement** `Region`, `CuisineEntry`, `InvalidCuisineException` and `CuisineCatalog` per the Interfaces block. Parse the CSV with plain line splitting on `,`; no field contains a comma. Fail fast with `IllegalStateException` on an unknown region or a duplicate key.
- [ ] **Step 5: Run it again.** `./mvnw test "-Dtest=CuisineCatalogTest"` should pass, 10/10.
- [ ] **Step 6: Commit:** `feat(backend): curated cuisine catalogue`.

---

### Task 2: Multi-cuisine and region browse filtering (backend)

**Files:**
- Modify: `.../model/RecipeFilter.java`, `.../specification/RecipeSpecifications.java`, `.../service/RecipeService.java`
- Test: `.../controller/RecipeControllerTest.java` (extend), `.../service/RecipeServiceBrowseTest.java` (new)

**Interfaces:**
- Consumes: `CuisineCatalog.resolveFilter` (Task 1); `TextArrayFunctions.TEXT_ARRAY_CONTAINS` (existing).
- Produces:
  - `record RecipeFilter(List<String> cuisine, List<String> region, Boolean dairyFree, Boolean glutenFree, Boolean vegan, Boolean vegetarian, Integer maxReadyInMinutes, Double minRating)`. The component names `cuisine`/`region` are the URL param names. `isEmpty()` now accounts for the lists, where null or empty counts as unset.
  - `RecipeSpecifications.withFilter(RecipeFilter f, Optional<Set<String>> cuisineKeys)`. Empty Optional means no cuisine predicate. An empty set gives `cb.disjunction()` (matches nothing). Otherwise it ORs one `text_array_contains(root.cuisines, key)` per key, ANDed with the other predicates.
  - `RecipeService` gains a `CuisineCatalog` constructor dependency. `browse` calls `catalog.resolveFilter(filter.cuisine(), filter.region())` and passes the result to the specification.

- [ ] **Step 1: Write tests.**
  - `RecipeControllerTest`: `browse_bindsRepeatedCuisineAndRegionParams`. A GET to `/api/recipes?cuisine=italian&cuisine=greek&region=asia` captures `RecipeFilter` with `cuisine() == [italian, greek]` and `region() == [asia]`.
  - `RecipeControllerTest`: `browse_singleCuisineStillBinds`. `?cuisine=thai` gives `cuisine() == [thai]`.
  - `RecipeServiceBrowseTest` (Mockito, real `CuisineCatalog`):
    - `onlyUnknownCuisinesReturnsEmptyPageWithoutQuerying`. With filter `cuisine=[lol]`, browse returns an empty page, and `recipeRepository.findAll(any(Specification.class), any(Pageable.class))` is either never called, or called with a spec that matches nothing. **Choose the former**: short-circuit to `Page.empty(pageable)` when the resolved set is present and empty.
    - `knownCuisineQueriesWithSpecification`. With filter `cuisine=[italian]`, `findAll(spec, pageable)` is called once.
- [ ] **Step 2: Run.** `./mvnw test "-Dtest=RecipeControllerTest,RecipeServiceBrowseTest"` fails (compile and assertions).
- [ ] **Step 3: Implement** per the Interfaces block. Update every `RecipeService` constructor call site in tests to pass a `CuisineCatalog`.
- [ ] **Step 4: Run.** `./mvnw test "-Dtest=RecipeControllerTest,RecipeServiceBrowseTest,CuisineCatalogTest,TextArrayFunctionsTest"` passes.
- [ ] **Step 5: Commit:** `feat(backend): any-of cuisine and region filtering`.

---

### Task 3: `GET /api/recipes/cuisines` (backend)

**Files:**
- Create: `.../model/projection/CuisineCount.java`, `.../model/displayDTO/CuisineInfoDto.java`, `.../service/CuisineService.java`, `.../controller/CuisineController.java`
- Modify: `.../repository/RecipeRepository.java`
- Test: `.../controller/CuisineControllerTest.java`, `.../service/CuisineServiceTest.java`

**Interfaces:**
- `interface CuisineCount { String getKey(); long getCount(); }`
- `RecipeRepository`: `@Query(value = "select c as key, count(*) as count from recipes, unnest(cuisines) c group by c", nativeQuery = true) List<CuisineCount> countCuisines();`
- `record CuisineInfoDto(String key, String label, String region, boolean mediterranean, long count)`. `region` is `Region.key()`.
- `CuisineService.listWithCounts()` returns `List<CuisineInfoDto>`: every catalogue entry with its count (0 if absent), sorted by count descending, then label ascending. DB keys not in the catalogue are ignored.
- `CuisineController` maps `@GetMapping("/api/recipes/cuisines")` to `listWithCounts()`.

- [ ] **Step 1: Write tests.**
  - `CuisineServiceTest` (mock repo returns `italian=12`, `greek=3`, `lol=5`):
    - the first two results are italian then greek
    - every catalogue entry is present
    - `lol` is absent
    - entries without counts have `count == 0`
  - `CuisineControllerTest` (`@WebMvcTest` + `@Import(SecurityConfig.class)` + mocked `JwtDecoder`, per existing pattern):
    - `anonymousGetsCatalogue`: GET `/api/recipes/cuisines` with no token gives 200 and `$[0].key == "italian"` from the mocked service.
    - `cuisinesPathIsNotTreatedAsRecipeId`: the same request never calls `RecipeService.getById`. Include `RecipeController` in the slice (`@WebMvcTest({CuisineController.class, RecipeController.class})`) with `RecipeService` mocked, then `verifyNoInteractions(recipeService)`.
- [ ] **Step 2: Run.** `./mvnw test "-Dtest=CuisineServiceTest,CuisineControllerTest"` fails.
- [ ] **Step 3: Implement** per Interfaces. `SecurityConfig` already permits `GET /api/recipes/**`; confirm via the anonymous test and don't edit the file.
- [ ] **Step 4: Run.** The same command passes.
- [ ] **Step 5: Commit:** `feat(backend): cuisines endpoint with live counts`.

---

### Task 4: Validate and normalize cuisines on save (backend)

**Files:**
- Modify: `.../service/RecipeService.java` (create and update), `.../exception/GlobalExceptionHandler.java`
- Test: `.../service/RecipeServiceCuisineValidationTest.java` (new), `.../exception/GlobalExceptionHandlerTest.java` (extend)

**Interfaces:**
- Consumes: `CuisineCatalog.normalizeAndValidate` (Task 1).
- Behavior:
  - `create` calls `recipe.setCuisines(catalog.normalizeAndValidate(dto.cuisines()))` before saving.
  - `update` sets cuisines the same way, replacing the current `dto.cuisines() != null ? ... : List.of()` line.
  - If the normalized list has more than 10 entries, throw `InvalidCuisineException` with message "At most 10 cuisines". Add a constructor `InvalidCuisineException(String message)` alongside the existing list constructor; for this case `unknown()` returns an empty list.
- `GlobalExceptionHandler` handles `InvalidCuisineException` with a 400 ProblemDetail: detail `"Validation failed"`, extension `errors = {"cuisines": ex.getMessage()}`.

- [ ] **Step 1: Write tests.**
  - `RecipeServiceCuisineValidationTest` (Mockito repos, real catalogue):
    - `createNormalizesAndDedupes`. Input `["  Italian ", "ITALIAN", "greek"]` saves a recipe with cuisines `[italian, greek]`; capture the saved `Recipe`.
    - `createRejectsUnknown`. Input `["italian", "lol"]` throws `InvalidCuisineException` mentioning `lol`, and `recipeRepository.save` is never called.
    - `updateRejectsUnknown`. Same rule on update, with the owner check passing.
    - `moreThanTenAfterDedupeRejected`. Eleven distinct valid keys throw.
  - `GlobalExceptionHandlerTest`: `invalidCuisineMapsTo400WithCuisinesFieldError`.
- [ ] **Step 2: Run.** `./mvnw test "-Dtest=RecipeServiceCuisineValidationTest,GlobalExceptionHandlerTest"` fails.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** the backend regression list:

  `./mvnw test "-Dtest=CuisineCatalogTest,RecipeServiceBrowseTest,CuisineServiceTest,CuisineControllerTest,RecipeServiceCuisineValidationTest,TextArrayFunctionsTest,SpoonacularPropertiesTest,SpoonacularRecipeTranslatorTest,SpoonacularClientTest,SpoonacularImportServiceTest,SpoonacularImportRunnerTest,CurrentUserIdArgumentResolverTest,GlobalExceptionHandlerTest,UserControllerTest,RecipeControllerTest,RatingControllerTest,CommentControllerTest,ReactionControllerTest"`

  Everything should pass.
- [ ] **Step 5: Commit:** `feat(backend): validate and normalize recipe cuisines`.

---

### Task 5: Opt-in real-Postgres query tests (backend)

**Files:**
- Test: `.../repository/RecipeCuisineQueryDbTest.java`

**Interfaces:** Consumes `RecipeSpecifications.withFilter`, `RecipeRepository.countCuisines`.

- [ ] **Step 1: Write the test class.** Annotations: `@Tag("db")`, `@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")`, `@DataJpaTest` with `@AutoConfigureTestDatabase(replace = NONE)`, and `@TestPropertySource(properties = "spring.flyway.enabled=false")`. Import `TextArrayFunctions` registration happens via ServiceLoader. Use the Boot 4 package names that resolve in this project.
  - `anyOfFilterReturnsOnlyMatchingRecipes`. `findAll(withFilter(filterWithNothingElse, Optional.of(Set.of("italian", "greek"))))` returns a non-empty list, and every recipe's cuisines contain `italian` or `greek`.
  - `anyOfFilterCombinesWithDietFlag`. `vegetarian=true` plus `{italian}` returns only vegetarian Italian recipes. Check that every result `isVegetarian()`.
  - `countCuisinesReturnsPositiveCounts`. The result contains `italian` with a count > 0.
  - The tests are read-only (`@DataJpaTest` rolls back anyway).
- [ ] **Step 2: Run.** `./mvnw test "-Dtest=RecipeCuisineQueryDbTest"` **without** the env var shows 0 run and 3 skipped. With `SUPABASE_DB_PASSWORD` exported from `.idea/workspace.xml`, all 3 pass. Never print the password.
- [ ] **Step 3: Commit:** `test(backend): opt-in real-Postgres cuisine query tests`.

---

### Task 6: Importer replace mode and catalogue-aware translator (backend)

**Files:**
- Modify: `.../spoonacular/SpoonacularImportProperties.java`, `SpoonacularRecipeTranslator.java`, `SpoonacularConfig.java`, `SpoonacularImportService.java`, `ImportSummary.java`, `SpoonacularImportRunner.java`
- Modify: `.../repository/RecipeRepository.java`
- Modify: `src/main/resources/application.properties`. This is a user-edited file, so stage only your hunks.
- Create: `.../spoonacular/SpoonacularImportCleaner.java`
- Test: extend `SpoonacularImportServiceTest`, `SpoonacularRecipeTranslatorTest`, `SpoonacularPropertiesTest`, `SpoonacularImportRunnerTest`

**Interfaces:**
- `SpoonacularImportProperties` gains `@DefaultValue("false") boolean replaceExisting` as its **last** component.
- `RecipeRepository`: `@Modifying @Query(value = "delete from recipes where source = 'spoonacular'", nativeQuery = true) int deleteAllImported();`
- `@Component SpoonacularImportCleaner` with `@Transactional int deleteAllImported()`, delegating to the repository.
- `SpoonacularImportService` gains a `SpoonacularImportCleaner` dependency. `runImport()` first runs `int deleted = properties.replaceExisting() ? cleaner.deleteAllImported() : 0;`, before any fetch.
- `ImportSummary(List<CuisineResult> results, boolean quotaExhausted, int deleted)`. The runner logs `deleted` when it is above 0.
- `SpoonacularRecipeTranslator(CuisineCatalog catalog)`. `mapCuisines` keeps only `catalog.contains(normalize(tag))` tags, normalized. If none remain, it falls back to `normalize(searchedCuisine)`. `SpoonacularConfig` injects the catalogue bean.
- `application.properties`:
  - `spoonacular.import.cuisines=` set to the 27 lowercase Spoonacular names (comma-separated, spaces kept as in the catalogue: `eastern european`, `latin american`, `middle eastern`)
  - `spoonacular.import.recipes-per-cuisine=10`
  - `spoonacular.import.replace-existing=false`

- [ ] **Step 1: Write tests.**
  - Service:
    - `replaceModeDeletesImportedBeforeFetching`. Use `InOrder`: `cleaner.deleteAllImported()` comes before `client.searchByCuisine(...)`, and `summary.deleted()` equals the cleaner's return.
    - `defaultModeNeverDeletes`. `verify(cleaner, never()).deleteAllImported()`.
  - Translator:
    - `unknownTagsAreDroppedKnownKept`. A Result with cuisines `["Mediterranean", "Tex-Mex Fusion"]` gives `[mediterranean]`.
    - `allUnknownFallsBackToSearchedCuisine`. `["Tex-Mex Fusion"]` searched as `"Thai"` gives `[thai]`.
  - Properties: `replaceExistingDefaultsFalseAndBinds`.
  - Update existing constructor calls: `new SpoonacularRecipeTranslator(new CuisineCatalog())`, `new SpoonacularImportProperties(..., false)`, and the 3-arg `ImportSummary`.
- [ ] **Step 2: Run** the 5 Spoonacular test classes. They fail.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** the full backend regression list from Task 4 Step 4. It passes.
- [ ] **Step 5: Commit:** `feat(backend): import replace mode and catalogue-aware translator`.

---

### Task 7: Golden Hour design system and shared components (frontend)

**Files:**
- Modify: `recipe-app-frontend/package.json` (add `lucide-react`, `@fontsource-variable/fraunces`), `src/index.css`, `src/components/ui.tsx`, `AppShell.tsx`, `RecipeCard.tsx`, `StarDisplay.tsx`, `Pagination.tsx`
- Test: `src/test/noEmoji.ts` (helper), extend `src/features/browse/browse.test.tsx`

**Interfaces (Produces):**
- Tailwind tokens `cream`, `paper`, `olive`, `saffron`, `terracotta`, `sand`, `muted`, with `font-serif` = Fraunces Variable and `font-sans` = Inter Variable.
- `ui.tsx` exports `Button` (variants `primary` | `outline` | `danger` | `ghost`), `Field`, `TextInput`, `SectionHeading`, `Spinner`, plus new `Chip({selected, onClick, children, count?})` and `Pill`. Existing prop names are unchanged.
- `src/test/noEmoji.ts`: `export function expectNoEmoji(root: HTMLElement = document.body) { expect(root.textContent ?? '').not.toMatch(/\p{Extended_Pictographic}/u); }`

**Visual direction** (spec §1; mockup `visual-direction.html` option A):
- cream page with a paper header bar
- serif "Plateful." wordmark with a saffron dot
- rounded 16–20px paper cards with soft olive-tinted shadow and a hover lift
- pill buttons (olive primary)
- saffron ★ in `StarDisplay`
- the missing-photo placeholder uses the lucide `UtensilsCrossed` icon on sand
- `RecipeCard` shows `Clock` and the ready time
- Pagination is pill-shaped (olive for the current page)
- Keep every existing accessible name and role.

- [ ] **Step 1: Add the test** `browse renders without emoji characters` to `browse.test.tsx`: render `/` with one recipe, wait for the card, then call `expectNoEmoji()`.
- [ ] **Step 2: Run.** `npx vitest run src/features/browse` fails: the current card placeholder and pagination use emoji or entity glyphs. If it already passes, it is still kept as a guard.
- [ ] **Step 3: Implement.** Install the deps, set the tokens and fonts, restyle the shared components, and replace emojis with lucide icons.
- [ ] **Step 4: Run.** `npx vitest run` passes (42 + 1) and `npm run build` is clean.
- [ ] **Step 5: Commit:** `feat(frontend): Golden Hour design system and shared components`.

---

### Task 8: Restyle all pages, no emojis (frontend)

**Files:**
- Modify: `src/features/recipe/RecipeDetailPage.tsx`, `RatingBox.tsx`, `CommentsSection.tsx`, `src/components/ConfirmDialog.tsx`, `src/features/authoring/RecipeFormPage.tsx` (styling only; the picker is Task 11), `src/features/profile/*.tsx`, `src/features/auth/*.tsx`
- Test: extend `detail.test.tsx`, `comments.test.tsx`, `authoring.test.tsx`, `profiles.test.tsx` with one `expectNoEmoji()` assertion each

**Visual direction (spec §1):**
- Detail page: two-column layout with a rounded sticky photo card and `Clock` / `Users` icons for time and servings; macros on sand pills; serif title with saffron stars.
- Steps numbered in serif terracotta numerals; ingredients as a soft checklist.
- Comments: soft bubbles with `ThumbsUp` / `ThumbsDown` icon buttons. Keep the aria-labels "Like comment" and "Dislike comment" exactly.
- Dialog on paper with rounded corners.
- Form and profile pages: rounded paper section cards.
- Auth pages: a centered paper card on cream with a serif heading.

- [ ] **Step 1: Add** the four no-emoji assertions (after each page's primary content has loaded).
- [ ] **Step 2: Run.** `npx vitest run` fails on detail and comments (🍽, ⏱, 👍/👎 in the current markup).
- [ ] **Step 3: Implement** the restyle. **Behavior, roles and accessible names stay unchanged.** Update an existing test only if it asserted an emoji glyph, and say so in the report.
- [ ] **Step 4: Run.** `npx vitest run` passes and `npm run build` is clean.
- [ ] **Step 5: Commit:** `feat(frontend): restyle pages in Golden Hour, remove emojis`.

---

### Task 9: Cuisines API, multi-value filter params, URL helpers (frontend)

**Files:**
- Create: `src/api/cuisines.ts`, `src/features/browse/filterParams.ts`
- Modify: `src/lib/types.ts`, `src/api/recipes.ts`
- Test: `src/features/browse/filterParams.test.ts`, extend `src/api/client.test.ts`

**Interfaces (Produces):**
- `interface CuisineInfo { key: string; label: string; region: string; mediterranean: boolean; count: number }`
- `getCuisines(): Promise<CuisineInfo[]>` calls GET `/api/recipes/cuisines`. Query key: `['cuisines']`.
- `RecipeFilter` (types.ts) becomes `{ cuisine?: string[]; region?: string[]; dairyFree?; glutenFree?; vegan?; vegetarian?; maxReadyInMinutes?: number; minRating?: number }`.
- `browseRecipes(filter, page, size = PAGE_SIZE)` appends array values as repeated params. The existing sort params are kept. The `size` override supports the drawer's live count (`size=1`).
- `filterParams.ts`:
  - `interface FilterState { cuisine: string[]; region: string[]; vegan: boolean; vegetarian: boolean; glutenFree: boolean; dairyFree: boolean; maxReadyInMinutes: number | null; minRating: number | null }`
  - `emptyFilters: FilterState`
  - `readFilters(params: URLSearchParams): FilterState`. Uses `getAll` for cuisine and region, deduped. Numbers are parsed defensively: max time must be an integer 1–600, otherwise null; rating must be 1–5 in 0.5 steps, otherwise null.
  - `writeFilters(state: FilterState, base?: URLSearchParams): URLSearchParams`. Omits unset values and deletes `page`.
  - `toApiFilter(state): RecipeFilter`
  - `countActive(state): number`. Each cuisine, region, true diet flag and set number counts 1.

- [ ] **Step 1: Write tests** (`filterParams.test.ts`):
  - `readsRepeatedCuisineAndRegion`. `?cuisine=italian&cuisine=greek&region=asia` gives arrays.
  - `dedupesRepeatedValues`.
  - `rejectsOutOfRangeNumbers`. `maxReadyInMinutes=0`, `=601`, `=abc` and `minRating=7`, `=3.3` all give null.
  - `writeOmitsUnsetAndResetsPage`. Writing over a base with `page=3` leaves no `page`, and false flags are absent.
  - `roundTrips`. `readFilters(writeFilters(s))` equals `s`.
  - `countActive` example: two cuisines, one region, vegan and minRating gives 5.
- [ ] **Step 2: Write tests** (`client.test.ts`): `browseRecipes sends repeated cuisine and region params` (assert `getAll('cuisine')`) and `getCuisines fetches the catalogue`.
- [ ] **Step 3: Run.** `npx vitest run src/features/browse/filterParams.test.ts src/api` fails.
- [ ] **Step 4: Implement** per Interfaces. Leave `BrowsePage` on its old parsing until Task 10. Keep TypeScript compiling by adapting `BrowsePage`'s call into the new types with a minimal change.
- [ ] **Step 5: Run.** `npx vitest run` passes and `npm run build` is clean.
- [ ] **Step 6: Commit:** `feat(frontend): cuisines API and multi-value filter params`.

---

### Task 10: Filter drawer, A–Z view, active chips (frontend)

**Files:**
- Create: `src/features/browse/FilterDrawer.tsx`, `CuisineAZ.tsx`, `ActiveFilterChips.tsx`, `regions.ts`
- Modify: `src/features/browse/BrowsePage.tsx`
- Delete: `src/features/browse/FilterBar.tsx`, along with the `CUISINES` and `cuisineLabel` exports. Usages move to the catalogue.
- Test: rewrite `src/features/browse/browse.test.tsx` filter tests around the drawer. Keep the non-filter tests (cards, empty state, pagination, busy state, garbage page).

**Interfaces:**
- Consumes: `getCuisines`, `FilterState`/`readFilters`/`writeFilters`/`toApiFilter`/`countActive` (Task 9), `Chip` (Task 7).
- `regions.ts`: `REGIONS = [{key:'mediterranean',label:'Mediterranean'}, {key:'europe',label:'Europe'}, {key:'asia',label:'Asia'}, {key:'americas',label:'Americas'}, {key:'africa',label:'Africa'}, {key:'middle_east',label:'Middle East'}, {key:'oceania',label:'Oceania'}]`
- `FilterDrawer({ open, onOpenChange, applied: FilterState, onApply: (s: FilterState) => void })`
  - Radix Dialog. Has the accessible title "Filters". Right side above 640px, a bottom sheet below that.
  - The draft is initialized from `applied` every time it opens.
  - Live count via `useQuery(['recipeCount', draft], () => browseRecipes(toApiFilter(draft), 0, 1))`, debounced 300ms. The button label is `Show ${n} recipes`, or "Show recipes" while loading or on error.
  - Clear all resets the draft to `emptyFilters`.
  - Popular list: top 10 of `cuisines` with `count > 0`.
  - "All cuisines A–Z (N)" switches to `CuisineAZ`.
  - Time and rating number inputs, each with an inline `role="alert"` hint when invalid. Invalid values are excluded from the draft (stored as null).
  - If the cuisines query fails, show "Couldn't load cuisines" plus a Retry button; the rest of the drawer still works.
- `CuisineAZ({ cuisines: CuisineInfo[], selected: string[], onToggle(key), onBack })`
  - Shows only `count > 0`, grouped by the first letter of `label`, with letter headings.
  - A search input labeled "Search cuisines" filters by label substring, case-insensitive.
  - A letter index jumps to its group.
  - Each row is a checkbox named by the label, with the count shown.
- `ActiveFilterChips({ state, cuisines, onChange })`: one removable chip per active value. The chip text uses the catalogue label (or the region label), and its aria-label is "Remove {label}".
- `BrowsePage`:
  - Hero line, then the Filters button (aria-label "Filters", badge = `countActive`), then the chips, then the grid.
  - The applied state comes from `readFilters(searchParams)`. `onApply` calls `setSearchParams(writeFilters(s, searchParams))`.

- [ ] **Step 1: Write tests** (MSW serves `/api/recipes` and `/api/recipes/cuisines`, with italian 12, greek 3, japanese 5, macedonian 1, german 0):
  - `opensAndClosesWithEscape`
  - `draftChangesDoNotTouchUrlUntilApply`. Tick Italian; the last recipes request has no `cuisine` param. Click "Show N recipes"; the request now has `cuisine=italian`.
  - `closingWithoutApplyDiscardsDraft`. Tick Greek, press Escape, then reopen. Greek is unticked, and no request carried `greek`. (Review Focus 3.)
  - `liveCountLabel`. The count handler returns `totalElements: 7` for `size=1`, so the button reads "Show 7 recipes".
  - `regionChipSendsRegionParam`. Tap "Europe" then apply; the request has `region=europe`.
  - `azShowsOnlyCuisinesWithRecipesAndSearches`. German (count 0) is absent. Searching "mac" shows only Macedonian. Ticking it, going back, then applying sends `cuisine=macedonian`.
  - `invalidTypedTimeIsExcluded`. Type 900 into "Max time (minutes)": an alert hint shows, and after applying the request has no `maxReadyInMinutes`.
  - `activeChipRemovesFilter`. Start at `/?cuisine=italian&vegan=true`; clicking "Remove Italian" makes the next request carry `vegan=true` and no cuisine.
  - `cuisinesErrorStillAllowsDietFilter`. The cuisines endpoint returns 500, so "Couldn't load cuisines" shows. Vegan can still be applied.
  - Add `expectNoEmoji()` once with the drawer open.
- [ ] **Step 2: Run.** `npx vitest run src/features/browse` fails.
- [ ] **Step 3: Implement** per Interfaces. Use the visual reference `filter-panel.html` option A and `cuisine-az.html`.
- [ ] **Step 4: Run.** `npx vitest run` passes and `npm run build` is clean.
- [ ] **Step 5: Commit:** `feat(frontend): filter drawer with regions, popular cuisines and A–Z view`.

---

### Task 11: Recipe-form cuisine picker (frontend)

**Files:**
- Create: `src/features/authoring/CuisinePicker.tsx`
- Modify: `src/features/authoring/RecipeFormPage.tsx` (replace the cuisine checkboxes)
- Test: extend `src/features/authoring/authoring.test.tsx`

**Interfaces:**
- `CuisinePicker({ value: string[], onChange: (keys: string[]) => void, error?: string })`
  - Reads `getCuisines()` and offers **all** catalogue entries, including count 0.
  - A combobox input labeled "Cuisines" filters by label; selecting an option adds its key.
  - Selected keys render as removable chips with the aria-label "Remove {label}".
  - When 10 are selected the input is disabled, with a hint "Up to 10 cuisines".
  - Error text renders with `role="alert"`.
  - If the cuisines load fails, show "Couldn't load cuisines" plus Retry.
- `RecipeFormPage` wires it to the `cuisines` field via `Controller` (react-hook-form). Server errors already map `cuisines` onto `errors.cuisines`.

- [ ] **Step 1: Write tests:**
  - `pickerSearchesAndAddsCuisine`. Typing "mac" and choosing Macedonian makes the submitted POST body contain `cuisines: ["macedonian"]`.
  - `pickerCapsAtTen`. After 10 selections the input is disabled.
  - `serverUnknownCuisineErrorShowsOnPicker`. A 400 with `errors: { cuisines: "Unknown cuisine: lol" }` shows that text.
  - `editPrefillsSelectedCuisines`. Editing a recipe with `cuisines: ['italian']` shows a chip named "Remove Italian".
- [ ] **Step 2: Run.** `npx vitest run src/features/authoring` fails.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run.** `npx vitest run` passes and `npm run build` is clean.
- [ ] **Step 5: Commit:** `feat(frontend): searchable cuisine picker in the recipe form`.

---

### Task 12: Data rebalance run (controller with the user, no subagent)

The JDK HttpClient cannot start inside the agent environment (loopback socket restriction), so **the user runs the import from IntelliJ**. The controller verifies the result over JDBC.

- [ ] **Step 1:** Show the user the current counts: total, imported, and user recipes, via a read-only JDBC probe. Ask for explicit confirmation to delete all imported recipes, with their ratings and comments, and re-import. **Stop until the user says yes.**
- [ ] **Step 2:** On a yes, the user restarts the backend with these program args: `--spoonacular.import.enabled=true --spoonacular.import.replace-existing=true`. Then they paste or confirm the log summary (deleted / imported / skipped / failed).
- [ ] **Step 3:** Verify read-only over JDBC:
  - zero imported recipes from before the run remain (compare `created_at` with the run time)
  - per-cuisine counts are about 10 for each of the 27
  - the user-recipe count is unchanged
- [ ] **Step 4:** Tell the user to remove both program args so normal starts don't re-run the import.
- [ ] **Step 5:** Append the outcome to the dev log, then commit and push.

---

### Task 13: Final verification, review, merge

- [ ] **Step 1:** Backend regression list (Task 4 Step 4, plus `RecipeCuisineQueryDbTest` with the env var), `npx vitest run`, and `npm run build`: all green.
- [ ] **Step 2:** The user reviews the restyled site in the browser (visual acceptance, spec §6). Iterate on feedback.
- [ ] **Step 3:** Final whole-branch review on the most capable model, then fix any findings.
- [ ] **Step 4:** Merge `feature/golden-hour` into `main`, verify, push, and delete the branch.
