# Seas filter and recipe sorting: design

Date: 2026-10-13
Status: approved in conversation; this written spec is awaiting review.
Round 1 of 3. The next rounds are the age check and then two-factor authentication.

## 1. Goal

**Seas:** browsing gets five "seas" alongside the regions. Mediterranean already exists; the new ones are Caribbean, North Sea, Sea of Japan & Pacific, and Gulf of Mexico.
- A sea matches every recipe whose cuisine comes from that sea's coasts. Seafood is not required.
- Picking a sea shows a short card describing its food: its vibe, star ingredients and icon dishes.

**Sorting:** people can sort the browse page by:
- **Newest** (the default, as today);
- **Most rated** (the most ratings);
- **Most popular** (the highest average rating);
- **Most comments**.

## 2. Decisions from the conversation

| Question | Decision |
|---|---|
| How do seas filter? | Like Mediterranean today: by coastal cuisine. A cuisine can belong to several seas. |
| What about the user's table text? | A "Seas" section in the drawer. Tapping a sea shows a card with its vibe, star ingredients and icon dishes. |
| Mediterranean | Moves from the Regions row into the Seas section. |
| Most popular | The highest average rating. Unrated recipes go last, and ties go to the recipe with more ratings. |
| Most rated | The most ratings. Ties go to the higher average rating. |
| Where is sorting? | A "Sort by" control next to the Filters button. It applies straight away, not through the drawer. |

## 3. Data

### 3.1 `cuisines.csv`

- The fourth column `mediterranean` (true/false) becomes `seas`: zero or more sea keys separated by `|`, for example `mediterranean|north_sea`. An empty field means no seas.
- The new header is `key,label,region,seas`.
- Sea keys are `mediterranean`, `caribbean`, `north_sea`, `pacific` and `gulf_of_mexico`.
- Membership:
  - **mediterranean:** every row that has `mediterranean=true` today, unchanged.
  - **caribbean:** caribbean, jamaican, cuban, haitian, dominican, puerto rican, trinidadian, bahamian, barbadian, antiguan, grenadian, belizean, costa rican, panamanian, honduran, nicaraguan, guatemalan, colombian, venezuelan.
  - **north_sea:** british, irish, norwegian, danish, dutch, belgian, german, french, swedish, icelandic, nordic.
  - **pacific:** japanese, korean, russian, chinese, taiwanese, filipino, hawaiian, australian, new zealand, chilean, peruvian, canadian, american, mexican.
  - **gulf_of_mexico:** mexican, cajun, southern, american, cuban.
  - Any of these keys missing from the catalogue is simply not tagged. The loader never invents rows.
- The loader rejects an unknown sea key at startup, the same way it rejects an unknown region today.

### 3.2 `seas.json`

`seas.json` is a new classpath resource. It is JSON rather than CSV, because the text contains commas. It holds an ordered array of the five seas:

```json
{ "key": "mediterranean", "label": "Mediterranean",
  "vibe": "Fresh, olive-oil drenched, citrusy, and simple.",
  "starIngredients": "Branzino, octopus, sardines, squid, mussels.",
  "iconDishes": "Spanish Paella, Grilled Branzino, Calamari." }
```

The text is copied verbatim from the user's table:

| key | label | vibe | starIngredients | iconDishes |
|---|---|---|---|---|
| mediterranean | Mediterranean | Fresh, olive-oil drenched, citrusy, and simple. | Branzino, octopus, sardines, squid, mussels. | Spanish Paella, Grilled Branzino, Calamari. |
| caribbean | Caribbean | Citrus-marinated, spicy, fried, and robust. | Spiny lobster, snapper, conch, mahi-mahi. | Conch Fritters, Jamaican Escovitch Fish. |
| north_sea | North Sea | Comforting, smoky, pickled, and hearty. | Cod, haddock, herring, langoustines. | British Fish & Chips, Scandinavian Pickled Herring. |
| pacific | Sea of Japan & Pacific | Umami-rich, clean, precise, often raw or lightly seared. | Salmon, mackerel, king crab, sea urchin (uni). | Sashimi, Miso-Glazed Salmon, Crab Hot Pot. |
| gulf_of_mexico | Gulf of Mexico | Cajun/Creole spiced, buttery, and deeply comforting. | Gulf shrimp, blue crab, oysters, redfish. | Shrimp Po' Boys, Seafood Gumbo, Oysters Rockefeller. |

At startup the loader checks that the keys are exactly the five sea keys, with no duplicates.

## 4. Backend

### 4.1 Catalogue

- `CuisineEntry(key, label, region, mediterranean)` becomes `CuisineEntry(key, label, region, List<String> seas)`.
- `CuisineCatalog` gains `keysForSea(String seaKey) -> Set<String>`.
- `resolveFilter(cuisines, regions)` becomes `resolveFilter(cuisines, regions, seas)`. It returns the union of:
  - the explicit cuisines;
  - the region members;
  - the sea members.

  The meanings stay the same: no values means no filter, and only-unknown values match nothing.
- **Backward compatibility:** `region=mediterranean` still resolves to the Mediterranean sea's members, so old links and bookmarks keep working.
- A new `SeaCatalog` (`@Component`) loads `seas.json` and exposes `all()`.

### 4.2 API

- `RecipeFilter` gains `List<String> sea` (the query param `sea`, repeatable).
- The cuisine endpoint `GET /api/recipes/cuisines` keeps `key`, `label`, `region` and `count`. Each entry swaps `mediterranean: boolean` for `seas: string[]`.
- New `GET /api/recipes/seas` returns `[{key, label, vibe, starIngredients, iconDishes}]` in file order.
  - It is public, already covered by the existing `GET /api/recipes/**` rule.
  - `cuisines`, the literal path, already takes priority over `{id}`, and so does `seas`. A test pins it.
- Browse takes `sortBy`, one of `newest | most-rated | top-rated | most-comments`.
  - When it's missing, it defaults to `newest`.
  - An unknown value gets a 400 with `errors = {"sortBy": "Unknown sort: <value>"}`.
  - The client no longer sends Spring's `sort` parameter. If it does, it is ignored.

### 4.3 Sorting

- The order is applied inside the Specification (`query.orderBy(...)`), with correlated subqueries over `recipe_ratings` and `recipe_comments`. No new columns, no denormalization.
- Spring Data clears `orderBy` for the count query, so paging totals stay correct.
- The service always passes an unsorted `PageRequest` (page and size only).
- Browse always goes through the Specification path, including when no filter is set, so the sort applies everywhere.

| sortBy | ORDER BY |
|---|---|
| newest | `created_at desc, id asc` |
| most-rated | `rating_count desc, avg_rating desc nulls last, created_at desc, id asc` |
| top-rated | `avg_rating desc nulls last, rating_count desc, created_at desc, id asc` |
| most-comments | `comment_count desc, created_at desc, id asc` |

`id asc` is the final tie-breaker, so paging is stable.

## 5. Frontend

- `src/api/cuisines.ts`:
  - `CuisineInfo` swaps `mediterranean` for `seas: string[]`.
  - A new `getSeas()` is cached under the query key `['seas']`.
  - It returns the type `SeaInfo { key, label, vibe, starIngredients, iconDishes }`.
- `filterParams.ts`:
  - `FilterState` gains `sea: string[]`, read and written as the URL param `sea` (repeatable).
  - `sea` is included in `countActive` and `toApiFilter`.
  - An old link carrying `region=mediterranean` is read as `sea=mediterranean`, so the chip shows up in the Seas section.
  - **Sort is separate from FilterState.** It lives in the URL param `sort` and is read with `readSort(params) -> SortKey`. Unknown values fall back to `newest`. Writing it deletes `page`.
- `browseRecipes(filter, page, size, sort)` sends `sortBy`, and no longer sends `sort=createdAt,desc&sort=id,asc`.
- **FilterDrawer:**
  - Mediterranean leaves the Regions chips, and `regions.ts` drops it.
  - A new **Seas** section sits below Regions, with five chips in `seas.json` order. Labels come from the seas endpoint. Before it loads, the labels come from a static fallback in `regions.ts`.
  - For each selected sea, an info card appears under the chips:
    - the sea name as a small serif heading;
    - **Vibe**, **Star ingredients** and **Icon dishes** as three labelled lines.
  - Golden Hour styling: a paper card, a sand ring, and labels in `text-olive/70`.
  - The live "Show N recipes" count includes seas.
- **ActiveFilterChips** shows the selected seas, removable with "Remove {label}".
- **BrowsePage:** a **Sort by** select labelled "Sort by" sits next to the Filters button. It is a native `<select>`, for accessibility and phone friendliness. Its options are Newest, Most rated, Most popular and Most comments. Changing it updates `?sort=` straight away and resets to page 1.
- **No emojis.**

## 6. Testing

**Backend:**
- `CuisineCatalogTest`:
  - the `seas` column parses;
  - an unknown sea key fails;
  - `keysForSea`;
  - `resolveFilter` combines cuisine, region and sea;
  - the `region=mediterranean` alias;
  - only-unknown sea values match nothing.
- `SeaCatalogTest`: five seas in order, with exact text for one of them.
- Controller tests:
  - `/api/recipes/seas` is public and returns 5;
  - `/api/recipes/cuisines` entries have `seas`;
  - `sortBy` binds;
  - an unknown `sortBy` gets a 400.
- A real-database test, with the same annotations as the other DB tests: each `sortBy` order holds over the first page. The checks:
  - **most-rated:** rating counts are non-increasing.
  - **top-rated:** non-null averages come before nulls, and are non-increasing.
  - **most-comments:** comment counts are non-increasing.
  - **newest:** `createdAt` is non-increasing.

  The counts are read back with the existing per-recipe queries.

**Frontend:**
- The Seas section renders five chips.
- Ticking Caribbean shows its card with the exact vibe text, and applying sends `sea=caribbean`.
- Mediterranean is no longer among the Regions chips.
- `?sea=north_sea` pre-ticks the chip and shows an active chip that removes it.
- The Sort select changes the URL to `?sort=most-rated`, drops `page`, and the request carries `sortBy=most-rated`.
- An unknown `?sort=` falls back to Newest.
- `expectNoEmoji()` with the drawer open.

## 7. Out of scope

- Seafood-only filtering.
- Sea pages or illustrations.
- Changing which cuisines the importer fetches.
- Denormalized rating and comment columns. Revisit only if sorting gets slow.
