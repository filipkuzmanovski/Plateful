# Seas Filter and Recipe Sorting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add five sea filters (with info cards), plus four sort orders on the browse page: Newest, Most rated, Most popular and Most comments.

**Architecture:**
- **Seas data:** the cuisine catalogue's `mediterranean` flag becomes a `seas` list. A small `seas.json` holds the five seas' text.
- **Filtering:** seas resolve to cuisine keys, exactly like regions do.
- **Sorting:** done in the browse Specification with correlated subqueries over ratings and comments, selected by a new `sortBy` request parameter.
- **Frontend:** a Seas section with cards in the filter drawer, and a "Sort by" select on the browse page.

**Tech Stack:**
- Spring Boot 4.1, Java 21, Hibernate 7.4 (JPA 3.2 Criteria with `Nulls`), Jackson.
- React 19, TS strict, TanStack Query v5, Vitest + RTL + MSW.

**Spec:** `docs/superpowers/specs/2026-10-13-seas-and-sorting-design.md`. It is the binding authority; read it alongside this plan.

## Global Constraints

**Seas:**
- Sea keys are exactly `mediterranean`, `caribbean`, `north_sea`, `pacific` and `gulf_of_mexico`.
- Labels are "Mediterranean", "Caribbean", "North Sea", "Sea of Japan & Pacific" and "Gulf of Mexico".
- Sea text is copied verbatim from spec §3.2.
- The `cuisines.csv` header becomes `key,label,region,seas`. `seas` is `|`-separated and may be empty.
- `region=mediterranean` stays a working alias for `sea=mediterranean`, both in the backend resolver and when the frontend reads the URL.

**Sorting:**
- `sortBy` takes one of `newest | most-rated | top-rated | most-comments`, defaulting to `newest`.
- An unknown value gets a 400 with `errors = {"sortBy": "Unknown sort: <value>"}`.
- The ORDER BY for each value is exactly as in spec §4.3, with `id asc` as the final tie-breaker and averages `nulls last`.
- UI sort labels:

  | Key | Label |
  |---|---|
  | `newest` | Newest |
  | `most-rated` | Most rated |
  | `top-rated` | Most popular |
  | `most-comments` | Most comments |

- The page URL param is `sort`. The API param is `sortBy`. The client no longer sends Spring's `sort`.

**UI rules:**
- No emojis (`expectNoEmoji()`).
- Secondary text `text-olive/70`, never `text-muted`.
- Clickable things are `<button>`s. The sort control is a native `<select>`.

**Files and commands:**
- NEVER stage or modify the user's uncommitted files: `SecurityConfig.java`, `WebConfig.java`, `RecipeRating.java`, `User.java`, `CurrentUserId.java`, `CurrentUserIdArgumentResolver.java`, `application.properties`. Stage explicit paths only.
- Backend tests: `export JAVA_HOME="$USERPROFILE/.jdks/ms-21.0.9"`, then `./mvnw -q test -Dtest=<Classes>` from `RecipeAppBackend/`. NEVER run unfiltered `mvnw test`. Confirm results via `target/surefire-reports` XML.
  - DB tests need `SUPABASE_DB_PASSWORD`, exported from `RecipeAppBackend/.idea/workspace.xml` without printing it.
  - DB test classes use EXACTLY the annotations of `RecipeCuisineQueryDbTest`.
- Frontend: run npm/npx via the Bash tool from `recipe-app-frontend/`. `npx vitest run` and `npm run build` must be green before each commit.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Push after each task (branch `feature/seas-and-sorting`).

## Review Focus

1. **Old bookmarks with `?region=mediterranean`** must still filter to Mediterranean cuisines on the backend. The frontend shows them as the Mediterranean sea chip, not as an unknown region. Pinned in Task 1 and Task 4.
2. **Paging through a sorted, filtered list** must neither repeat nor skip recipes. `totalElements` must equal the unsorted count, because Spring clears ORDER BY for the count query. Pinned by the DB test in Task 3.
3. **Applying filters must keep the chosen sort, and changing the sort must reset to page 1.** Pinned in Task 4 and Task 6.
4. **A hand-edited `?sort=bogus` in the page URL** shows Newest and sends `sortBy=newest`, never a 400 error screen. Pinned in Task 4.
5. **If the seas endpoint fails,** the drawer still shows the five sea chips with fallback labels and still filters. Only the info cards are missing. Pinned in Task 5.

---

### Task 1: Seas in the catalogue and filter (backend)

**Files:**
- Modify: `RecipeAppBackend/src/main/resources/cuisines.csv` (header and fourth column, all rows)
- Create: `RecipeAppBackend/src/main/resources/seas.json`
- Modify: `.../cuisine/CuisineEntry.java`, `.../cuisine/CuisineCatalog.java`
- Create: `.../cuisine/Sea.java`, `.../cuisine/SeaCatalog.java`
- Modify: `.../model/RecipeFilter.java`: add `List<String> sea` after `region`, and include it in `isEmpty()`.
- Modify: `.../service/RecipeService.java`: `browse` passes `filter.sea()` to `resolveFilter`.
- Modify: `.../service/CuisineService.java` and `.../model/displayDTO/CuisineInfoDto.java`: `boolean mediterranean` becomes `List<String> seas`.
- Test: `.../cuisine/CuisineCatalogTest.java` (extend), `.../cuisine/SeaCatalogTest.java` (new), and existing tests that construct `RecipeFilter`, `CuisineEntry` or `CuisineInfoDto`. Find them with grep.

**Interfaces:**
- Produces:
  - `record CuisineEntry(String key, String label, Region region, List<String> seas)`.
  - `CuisineCatalog`:
    - `Set<String> keysForSea(String seaKey)`;
    - `Optional<Set<String>> resolveFilter(List<String> cuisines, List<String> regions, List<String> seas)`;
    - `static final Set<String> SEA_KEYS`, holding the five keys.
  - `record Sea(String key, String label, String vibe, String starIngredients, String iconDishes)`.
  - `@Component SeaCatalog`, with a no-arg constructor that loads `/seas.json` using a plain `new ObjectMapper()` from the Jackson already on the classpath, and `List<Sea> all()`.
  - `record CuisineInfoDto(String key, String label, String region, List<String> seas, long count)`.
  - `RecipeFilter.sea()`.

- [ ] **Step 1: Write the failing tests.**
  - `CuisineCatalogTest`:
    - `seasColumnParses`: `find("french").seas()` contains exactly `mediterranean` and `north_sea`. `find("mexican").seas()` contains exactly `gulf_of_mexico` and `pacific`. `find("swiss").seas()` is empty.
    - `keysForSeaCaribbean`: contains `jamaican` and `cuban`, but not `british`.
    - `mediterraneanSeaMatchesOldFlagRows`: `keysForSea("mediterranean")` contains `greek`, `lebanese`, `moroccan` and `italian`.
    - `regionMediterraneanIsAliasForSea` (Review Focus 1): `resolveFilter(null, List.of("mediterranean"), null)` equals `Optional.of(keysForSea("mediterranean"))`.
    - `resolveUnionOfAllThree`: `resolveFilter(List.of("thai"), List.of("africa"), List.of("north_sea"))` contains `thai`, `moroccan` and `british`.
    - `onlyUnknownSeaMatchesNothing`: `resolveFilter(null, null, List.of("atlantis"))` equals `Optional.of(Set.of())`.
    - `unknownSeaKeyInCsvFailsStartup`: a parser helper fed `"x,X,europe,lake"` throws `IllegalStateException`. Extract a package-private `static CuisineEntry parseLine(String line, int lineNo)` so this is testable.
  - `SeaCatalogTest`:
    - `fiveSeasInFileOrder`: the keys are `[mediterranean, caribbean, north_sea, pacific, gulf_of_mexico]`.
    - `northSeaTextVerbatim`: vibe `"Comforting, smoky, pickled, and hearty."`, star ingredients `"Cod, haddock, herring, langoustines."`, and icon dishes `"British Fish & Chips, Scandinavian Pickled Herring."`.
    - `pacificLabel` is `"Sea of Japan & Pacific"`.
- [ ] **Step 2: Run them to verify they fail.** Run: `./mvnw -q test -Dtest='CuisineCatalogTest,SeaCatalogTest'`. Expected: a compile failure.
- [ ] **Step 3: Rewrite the CSV's fourth column.** Use a one-off script, which must not be committed. Each row's seas are `mediterranean` if its old value was `true`, plus every membership list in spec §3.1 that names its key, joined with `|` in the order `mediterranean|caribbean|north_sea|pacific|gulf_of_mexico`. Write `seas.json` from the spec §3.2 table, verbatim.
- [ ] **Step 4: Implement** per the Interfaces block.
  - The loader validates each sea key against `SEA_KEYS`.
  - `SeaCatalog` validates that the file contains exactly the five keys, with no duplicates.
  - In `resolveFilter`, a region value equal to `"mediterranean"` is treated as the sea `mediterranean`. Unknown sea values add nothing.
  - Update every construction site the compiler flags.
- [ ] **Step 5: Run the regression list to verify it passes.** Run: `./mvnw -q test -Dtest='CuisineCatalogTest,SeaCatalogTest,CuisineServiceTest,CuisineControllerTest,RecipeServiceBrowseTest,RecipeServiceCuisineValidationTest,RecipeServicePhotosTest,RecipeControllerTest,SpoonacularRecipeTranslatorTest,SpoonacularImportServiceTest'`, then `-Dtest='*DbTest'`. Expected: all pass.
- [ ] **Step 6: Commit:** `feat(backend): seas in the cuisine catalogue and browse filter`.

### Task 2: Seas endpoint and cuisine DTO JSON (backend)

**Files:**
- Modify: `.../controller/CuisineController.java`: add `GET /api/recipes/seas`, returning `seaCatalog.all()`.
- Test: `.../controller/CuisineControllerTest.java`

**Interfaces:**
- Consumes: `SeaCatalog.all()` and `CuisineInfoDto.seas` from Task 1.
- Produces: JSON `GET /api/recipes/seas` → `[{key,label,vibe,starIngredients,iconDishes}]`. `GET /api/recipes/cuisines` entries carry `seas: []` and no longer `mediterranean`.

- [ ] **Step 1: Write the failing tests.**
  - `seasIsPublicAndReturnsFive`: anonymous `GET /api/recipes/seas` gives 200, `$.length()` is 5, `$[0].key` is `"mediterranean"`, and `$[3].label` is `"Sea of Japan & Pacific"`. Load the real `SeaCatalog` into the slice with `@Import(SeaCatalog.class)`, or mock it the way the existing test mocks `CuisineService`. Note which in the report.
  - `seasNotSwallowedByRecipeId`: the same request never calls `RecipeService.getById`.
  - `cuisinesCarrySeas`: `$[0].seas` is an array, and `$[0].mediterranean` does not exist.
- [ ] **Step 2: Run it to verify it fails.** Run: `./mvnw -q test -Dtest=CuisineControllerTest`. Expected: FAIL with 404 or a missing bean.
- [ ] **Step 3: Implement the endpoint.**
- [ ] **Step 4: Run it to verify it passes.** Same command. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(backend): GET /api/recipes/seas`.

### Task 3: Sort orders (backend)

**Files:**
- Create: `.../model/RecipeSort.java`
- Create: `.../exception/InvalidSortException.java`
- Modify: `.../exception/GlobalExceptionHandler.java`: add a handler next to `handleInvalidCuisine`.
- Modify: `.../specification/RecipeSpecifications.java`: add `withSort`.
- Modify: `.../service/RecipeService.java`:
  - `browse(RecipeFilter, RecipeSort, Pageable)` always uses `findAll(spec, PageRequest.of(page, size))`, both with and without a filter. The empty-cuisine-set short-circuit stays.
  - `getByAuthor` is unchanged.
- Modify: `.../controller/RecipeController.java`: `browse(@ModelAttribute RecipeFilter filter, @RequestParam(defaultValue = "newest") String sortBy, Pageable pageable)`, which maps `sortBy` through `RecipeSort.fromKey`.
- Test: `RecipeServiceBrowseTest`, `RecipeControllerTest`, `GlobalExceptionHandlerTest`, and new `.../repository/RecipeSortDbTest.java`.

**Interfaces:**
- Produces:
  - `enum RecipeSort { NEWEST, MOST_RATED, TOP_RATED, MOST_COMMENTS }` with `String key()`. The keys are `newest`, `most-rated`, `top-rated` and `most-comments`.
  - `static RecipeSort fromKey(String)`, which throws `InvalidSortException("Unknown sort: " + raw)`.
  - `RecipeSpecifications.withSort(RecipeSort sort): Specification<Recipe>`. It sets `query.orderBy(...)` and returns a null or conjunction predicate. Combine it with `withFilter(...)` using `.and(...)`.
  - The ORDER BY is exactly as in spec §4.3:
    - The rating count, average and comment count are correlated `Subquery<Long>` / `Subquery<Double>` over `RecipeRating` and `RecipeComment`, each with `where(cb.equal(x.get("recipe"), root))`.
    - Use `cb.desc(avgSubquery, Nulls.LAST)` (`jakarta.persistence.criteria.Nulls`, JPA 3.2).
    - `count` uses `cb.count`.
    - End with `cb.desc(root.get("createdAt"))`, then `cb.asc(root.get("id"))`.
  - Only set orderBy when `query.getResultType()` is not `Long`. This guards the count query: Spring clears its orderBy anyway, but this avoids building unused subqueries.

- [ ] **Step 1: Write the failing tests.**
  - `RecipeControllerTest`:
    - `browsePassesSortBy`: `GET /api/recipes?sortBy=most-rated` calls `recipeService.browse(any(), eq(RecipeSort.MOST_RATED), any())`.
    - `browseDefaultsToNewest`.
    - `unknownSortIs400`: `?sortBy=bogus` gives 400 with `$.errors.sortBy == "Unknown sort: bogus"`.
  - `RecipeServiceBrowseTest`:
    - `emptyFilterStillUsesSpecificationPath`: `findAll(any(Specification.class), any(Pageable.class))` is called, and `findAll(Pageable)` never.
    - `pageRequestIsUnsorted`: the captured `Pageable` has `getSort().isUnsorted()`.
  - `GlobalExceptionHandlerTest`: `InvalidSortException` gives 400 with `errors.sortBy`.
  - `RecipeSortDbTest` (Review Focus 2). For each `RecipeSort`, call `recipeRepository.findAll(RecipeSpecifications.withFilter(emptyFilter, Optional.empty()).and(RecipeSpecifications.withSort(s)), PageRequest.of(0, 50))` and assert:
    - `MOST_RATED`: `ratingRepository.countByRecipeId(id)` is non-increasing over the page.
    - `TOP_RATED`: `findAverageRatingByRecipeId(id)` has non-nulls first, and non-null values are non-increasing.
    - `MOST_COMMENTS`: `commentRepository.countByRecipeId(id)` is non-increasing.
    - `NEWEST`: `createdAt` is non-increasing.
    - `pagesDoNotOverlap`: for `MOST_RATED` at size 20, the page 0 and page 1 id sets are disjoint, and `totalElements` equals `recipeRepository.count()`.
- [ ] **Step 2: Run them to verify they fail.** Run: `./mvnw -q test -Dtest='RecipeControllerTest,RecipeServiceBrowseTest,GlobalExceptionHandlerTest'`. Expected: a compile failure.
- [ ] **Step 3: Implement** per the Interfaces block. If Hibernate rejects subqueries in ORDER BY at runtime, the DB test shows it. In that case report BLOCKED with the exception, and do not switch approach unilaterally.
- [ ] **Step 4: Run the Task 1 regression list plus `RecipeSortDbTest` and `*DbTest`.** Expected: all pass.
- [ ] **Step 5: Commit:** `feat(backend): browse sort orders (newest, most rated, top rated, most comments)`.

### Task 4: Frontend API, URL state for seas and sort

**Files:**
- Modify: `recipe-app-frontend/src/api/cuisines.ts`:
  - `CuisineInfo.seas: string[]` replaces `mediterranean`.
  - Add `SeaInfo`, `getSeas()` and `seasQueryKey = ['seas']`.
- Modify: `recipe-app-frontend/src/lib/types.ts`: `RecipeFilter` gains `sea?: string[]`.
- Modify: `recipe-app-frontend/src/api/recipes.ts`:
  - The signature becomes `browseRecipes(filter, page, size = PAGE_SIZE, sort: SortKey = 'newest')`.
  - It sends `sortBy`, and stops sending the two `sort` params.
- Modify: `recipe-app-frontend/src/features/browse/filterParams.ts`:
  - `FilterState.sea`;
  - `readFilters` maps `region=mediterranean` to `sea`;
  - `writeFilters` writes `sea`;
  - `countActive` and `toApiFilter` include `sea`;
  - add `SORT_KEYS`, `SortKey`, `SORT_LABELS`, `readSort(params): SortKey` and `writeSort(sort, base): URLSearchParams`. `writeSort` removes `page`, and drops `sort` entirely when the value is `newest`.
- Modify: `recipe-app-frontend/src/features/browse/regions.ts`:
  - remove `mediterranean` from `REGIONS`;
  - add `SEAS_FALLBACK: readonly {key,label}[]`, the five seas in order with the spec labels;
  - add `seaLabel(key)`.
- Modify: the test fixtures and any test referencing `mediterranean` in `CuisineInfo` (`grep -rn mediterranean src`).
- Test: `src/features/browse/filterParams.test.ts` (create it if absent) and `src/api/client.test.ts` (adjust).

**Interfaces:**
- Produces:
  - `type SortKey = 'newest' | 'most-rated' | 'top-rated' | 'most-comments'`.
  - `SORT_LABELS: Record<SortKey, string>`, with labels as in the Global Constraints.
  - `readSort` and `writeSort`.
  - `FilterState.sea: string[]`.
  - `getSeas(): Promise<SeaInfo[]>`.
  - `SEAS_FALLBACK` and `seaLabel`.

- [ ] **Step 1: Write the failing tests.**
  - `readFilters(new URLSearchParams('region=mediterranean&region=asia'))` gives `sea: ['mediterranean']` and `region: ['asia']` (Review Focus 1).
  - `writeFilters({...emptyFilters, sea: ['caribbean']}, new URLSearchParams('sort=most-rated&page=3'))` gives `'sort=most-rated&sea=caribbean'`. The sort is kept and the page dropped (Review Focus 3).
  - `readSort` of `'sort=bogus'` is `'newest'` (Review Focus 4). `readSort` of `'sort=top-rated'` is `'top-rated'`.
  - `writeSort('most-comments', new URLSearchParams('page=2&vegan=true'))` gives `'vegan=true&sort=most-comments'`. `writeSort('newest', new URLSearchParams('sort=most-rated'))` gives `''`.
  - `countActive({...emptyFilters, sea: ['pacific', 'caribbean']})` is 2.
  - `browseRecipes({}, 0, 12, 'most-rated')` requests a URL containing `sortBy=most-rated` and no `sort=`. Assert via MSW request capture.
- [ ] **Step 2: Run them to verify they fail.** Run: `npx vitest run src/features/browse/filterParams.test.ts src/api`. Expected: FAIL.
- [ ] **Step 3: Implement** per the Interfaces block. Keep callers compiling: `BrowsePage` passes `readSort(params)` to `browseRecipes`.
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(frontend): sea and sort URL state; sortBy on browse requests`.

### Task 5: Seas section with info cards in the filter drawer (frontend)

**Files:**
- Modify: `recipe-app-frontend/src/features/browse/FilterDrawer.tsx`: a Seas section below Regions, plus the cards.
- Create: `recipe-app-frontend/src/features/browse/SeaCard.tsx`
- Modify: `recipe-app-frontend/src/features/browse/ActiveFilterChips.tsx`: sea chips, labelled "Remove {label}" through `seaLabel`.
- Test: `recipe-app-frontend/src/features/browse/browse.test.tsx`

**Interfaces:**
- Consumes: from Task 4, `getSeas`, `seasQueryKey`, `SEAS_FALLBACK`, `seaLabel` and `FilterState.sea`.
- Produces:
  - `SeaCard({ sea }: { sea: SeaInfo })`. It renders the label as a small serif heading, and three rows labelled "Vibe", "Star ingredients" and "Icon dishes", each label in `text-olive/70`.
  - The Seas section heading is "Seas". Chips use the shared `Chip` (aria-pressed). The order and labels come from `getSeas()`, falling back to `SEAS_FALLBACK`.
  - One `SeaCard` appears per selected sea, in sea order, only when the seas data has loaded.

- [ ] **Step 1: Write the failing tests** in `browse.test.tsx`. Add an MSW handler for `/api/recipes/seas` returning the five spec seas.
  - `seasSectionShowsFiveChips`: within the "Seas" group there are 5 chips. Within the Regions group there is no "Mediterranean" chip.
  - `tickingCaribbeanShowsCardAndFilters`:
    - Click Caribbean. The text `"Citrus-marinated, spicy, fried, and robust."` is visible.
    - Apply. The request has `sea=caribbean`.
  - `urlSeaPreTicksAndActiveChipRemoves`: start at `/?sea=north_sea`. The North Sea chip is pressed in the drawer. Clicking "Remove North Sea" sends the next request without `sea`.
  - `seasEndpointFailureFallsBack` (Review Focus 5): `/api/recipes/seas` returns 500. The five chips still render with fallback labels, ticking Gulf of Mexico shows no card, and applying sends `sea=gulf_of_mexico`.
  - `expectNoEmoji()` with a card open.
- [ ] **Step 2: Run them to verify they fail.** Run: `npx vitest run src/features/browse`. Expected: FAIL.
- [ ] **Step 3: Implement** with Golden Hour styling: card `bg-paper ring-1 ring-sand rounded-2xl p-4`, and the heading in `font-serif`.
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(frontend): seas section with info cards in the filter drawer`.

### Task 6: Sort by control on the browse page (frontend)

**Files:**
- Modify: `recipe-app-frontend/src/features/browse/BrowsePage.tsx`
- Test: `recipe-app-frontend/src/features/browse/browse.test.tsx`

**Interfaces:**
- Consumes: from Task 4, `readSort`, `writeSort`, `SORT_KEYS`, `SORT_LABELS`, and `browseRecipes(..., sort)`.
- Produces:
  - A native `<select>` with an accessible label "Sort by" (a visible label or `aria-label`), next to the Filters button.
  - Options in the order of `SORT_KEYS`, with labels from `SORT_LABELS`.
  - On change it calls `setParams(writeSort(value, params))`.
  - The browse query key includes the sort.

- [ ] **Step 1: Write the failing tests.**
  - `sortSelectChangesUrlAndRequest` (Review Focus 3): start at `/?page=2`. Select "Most rated". The next request has `sortBy=most-rated` and `page=0`, and the select shows "Most rated".
  - `applyingFiltersKeepsSort`: start at `/?sort=top-rated`. Apply Vegan in the drawer. The request has `sortBy=top-rated` and `vegan=true`.
  - `bogusSortShowsNewest` (Review Focus 4): start at `/?sort=bogus`. The select value is "Newest", and the request has `sortBy=newest`.
  - The select's options are exactly `['Newest', 'Most rated', 'Most popular', 'Most comments']`.
- [ ] **Step 2: Run them to verify they fail.** Run: `npx vitest run src/features/browse`. Expected: FAIL.
- [ ] **Step 3: Implement.** Style the select like `TextInput` (sand border, paper background, rounded-xl), with a lucide `ArrowUpDown` icon beside it, decorative (`aria-hidden`).
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(frontend): sort by newest, most rated, most popular and most comments`.

### Task 7: Verification and dev log

**Files:**
- Modify: `docs/plateful-frontend-dev-log.txt`. Append "PART 4 — SEAS AND SORTING", one section per task, in the existing plain-text style.

- [ ] **Step 1: Run the full verification.**
  - Backend: the Task 1 regression list plus `CuisineControllerTest`, `GlobalExceptionHandlerTest`, `CommentControllerTest`, `RatingControllerTest`, `ReactionControllerTest`, `UserControllerTest`, `CuisineCatalogTest`, `SeaCatalogTest`, `RecipePhotoPathsTest`, `TextArrayFunctionsTest`, `CurrentUserIdArgumentResolverTest`, `SpoonacularClientTest`, `SpoonacularPropertiesTest` and `SpoonacularImportRunnerTest`, then `*DbTest`.
  - Frontend: `npx vitest run` and `npm run build`.
  - Expected: all green.
- [ ] **Step 2: Write the dev log section, then commit:** `docs: dev log for seas and sorting`. Push.
