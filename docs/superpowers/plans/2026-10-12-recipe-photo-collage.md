# Recipe Photo Collage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Users upload up to 6 photos per recipe, which show as a collage with a full-screen viewer. "Source name" and "Source URL" can no longer be set by users.

**Architecture:**
- **Upload:** the browser shrinks each photo and uploads it straight to the Supabase Storage bucket `recipe-photos`, into the user's own folder.
- **Save:** the Spring API receives the ordered list of storage paths. It validates them against the caller's folder, stores them in a new `recipe_photos` table, and sets `recipes.image` to the cover's public URL.
- **Read:** the recipe response gains a `photos` list of public URLs, which the detail page renders as a collage.

**Tech Stack:**
- Spring Boot 4.1, Java 21, JPA/Hibernate 7, Flyway, Postgres (Supabase).
- React 19, TypeScript strict, TanStack Query v5, react-hook-form + zod v4, Radix Dialog, lucide-react, supabase-js v2 (auth + storage).
- Vitest + RTL + MSW for frontend tests.

**Spec:** `docs/superpowers/specs/2026-10-12-recipe-photo-collage-design.md`. It is the binding authority; read it alongside this plan.

## Global Constraints

**Limits and formats:**
- Max 6 photos per recipe. Position 0 is the cover.
- Bucket `recipe-photos`: public read, `file_size_limit` 5242880 (5 MB), MIME types `image/jpeg`, `image/png`, `image/webp`.
- Storage path format: `{userId}/{uuid}.{ext}`, where `ext` is one of `jpg|png|webp`. The backend regex is `^{callerId}/[0-9a-fA-F-]{36}\.(jpg|png|webp)$`.
- Public URL = `app.storage.photos-public-base-url + "/" + path`. The base is `https://bwnbvtbhcmeifsmlvmgl.supabase.co/storage/v1/object/public/recipe-photos`.
- Shrinking: longest side at most 2000 px, WebP at quality 0.85, falling back to JPEG.
- Validation errors are 400 with detail "Validation failed" and `errors = {"photos": "<message>"}`. They mirror `handleInvalidCuisine` in `GlobalExceptionHandler`.
- On update, the owner check (`assertOwnedBy`) runs before any photo validation.
- `sourceName`, `sourceUrl` and a user-supplied `image` are never taken from the request. They are removed from `RecipeCreateDto`; Jackson ignores unknown JSON properties.

**UI rules:**
- No emojis anywhere in the UI (`expectNoEmoji()` in the tests).
- Secondary text uses `text-olive/70`, never `text-muted`.
- Clickable things are `<button>`s.

**Files and commands:**
- NEVER stage or modify the user's uncommitted files: `SecurityConfig.java`, `WebConfig.java`, `RecipeRating.java`, `User.java`, `CurrentUserId.java`, `CurrentUserIdArgumentResolver.java`, `application.properties`.
  - The single exception: Task 3 adds ONE line to `application.properties` and stages only that line, with a trimmed patch and `git apply --cached`. Never `git add` the whole file.
- Stage files by explicit path; never `git add -A` or `git add .`.
- Backend: `export JAVA_HOME="$USERPROFILE/.jdks/ms-21.0.9"`, then `./mvnw -q test -Dtest=<Classes>` from `RecipeAppBackend/`. NEVER run unfiltered `mvnw test`.
  - DB tests (`@Tag("db")`) need `SUPABASE_DB_PASSWORD`. The controller exports it from `RecipeAppBackend/.idea/workspace.xml` without printing it.
- Frontend: run npm/npx via the Bash tool from `recipe-app-frontend/`. `npx vitest run` and `npm run build` must be green before each commit.
- DB test classes use EXACTLY the same annotations as `RecipeCuisineQueryDbTest`, including `@TestPropertySource(properties = "spring.flyway.enabled=false")`. Then the Spring context is shared and stays under Supabase's 15-client pool.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Push after each task.

## Review Focus

1. **Editing a recipe that keeps its photos must not fail on `UNIQUE (recipe_id, position)`.** Hibernate flushes inserts before deletes, so a derived `deleteByRecipeId` followed by `saveAll` collides. The delete must be a `@Modifying` JPQL bulk delete that runs immediately. Pinned by the rolled-back real-DB test in Task 1.
2. **Paths that aren't the caller's own uploads must be rejected:** another user's folder, `../`, a full `https://` URL, the wrong extension, duplicates, or more than 6. Each gives a 400 on `photos`, never a 500 and never silent acceptance. Pinned in Task 2.
3. **An older user recipe whose `image` is a pasted link, with no photo rows, must keep that image when edited without uploading photos.** Rule: on update, if `photos` is empty AND the recipe has no photo rows, leave `image` unchanged. The edit form doesn't show such a link as a tile, because only bucket URLs become tiles. Pinned in Task 3 and Task 6.
4. **A file the browser can't decode** (a corrupt file, or HEIC on a browser without HEIC support) **or a failed upload** must show an error state on that tile with Retry/Remove. It must never crash the form or leave Save enabled. Pinned in Task 5.
5. **Picking more files than the remaining slots:** e.g. 4 photos already, then 3 selected. The first 2 are accepted, and the message "You can add up to 6 photos." appears. Pinned in Task 5.

---

### Task 1: Storage setup SQL, `recipe_photos` table, entity and repository (backend)

**Files:**
- Create: `docs/supabase/recipe-photos-storage.sql`
- Create: `RecipeAppBackend/src/main/resources/db/migration/V3__recipe_photos.sql`
- Create: `RecipeAppBackend/src/main/java/mk/ukim/finki/wp/recipeappbackend/model/entities/RecipePhoto.java`
- Create: `RecipeAppBackend/src/main/java/mk/ukim/finki/wp/recipeappbackend/repository/RecipePhotoRepository.java`
- Test: `RecipeAppBackend/src/test/java/mk/ukim/finki/wp/recipeappbackend/repository/RecipePhotoRepositoryDbTest.java`

**Interfaces:**
- Produces:
  - `RecipePhoto` entity (`@Table(name = "recipe_photos")`). Follow the `RecipeIngredient` pattern: Lombok, a one-directional `@ManyToOne(fetch = LAZY) Recipe recipe`. Fields `UUID id`, `String path`, `Integer position`.
  - `RecipePhotoRepository extends JpaRepository<RecipePhoto, UUID>`, with:
    - `List<RecipePhoto> findByRecipeIdOrderByPositionAsc(UUID recipeId)`
    - `List<RecipePhoto> findByRecipeIdInOrderByPositionAsc(Collection<UUID> recipeIds)`
    - `@Modifying @Query("DELETE FROM RecipePhoto p WHERE p.recipe.id = :recipeId") void deleteAllByRecipeId(@Param("recipeId") UUID recipeId)`. This is a bulk delete; see Review Focus 1.
  - `boolean existsByRecipeId(UUID recipeId)`.

Note for the controller: once V3 sits in `target/classes`, the user's devtools backend applies it on its next restart. That's an additive table, and spec §9 approves it. Mention it to the user.

- [ ] **Step 1: Write `docs/supabase/recipe-photos-storage.sql`** per spec §3.
  - Bucket insert with `on conflict (id) do nothing`.
  - `drop policy if exists` plus `create policy` for INSERT and DELETE on `storage.objects`: `to authenticated`, `bucket_id = 'recipe-photos' and (storage.foldername(name))[1] = auth.uid()::text`.
  - No UPDATE policy.
  - A header comment saying to run it once in the Supabase SQL editor.
- [ ] **Step 2: Write `V3__recipe_photos.sql`** exactly as in spec §4.1.
- [ ] **Step 3: Write the failing DB test.**
  - Annotations: the same as `RecipeCuisineQueryDbTest`.
  - `@DataJpaTest` rolls back each test; nothing is committed. Add a class javadoc saying so.
  - `replaceKeepsPositionsUniqueAndOrdered()`:
    - Pick any existing recipe with `recipeRepository.findAll(PageRequest.of(0, 1))`.
    - Save rows at positions 0, 1, 2 with paths `"a"`, `"b"`, `"c"`.
    - Call `deleteAllByRecipeId(id)`, then save rows at positions 0 and 1 with paths `"c"` and `"a"`.
    - Call `flush()`.
    - Assert `findByRecipeIdOrderByPositionAsc(id)` maps to paths `["c", "a"]`.
  - `batchedFindGroupsByRecipe()`: `findByRecipeIdInOrderByPositionAsc(List.of(randomUUID()))` is empty.
- [ ] **Step 4: Run it to verify it fails.** Run: `./mvnw -q test -Dtest=RecipePhotoRepositoryDbTest` (with `SUPABASE_DB_PASSWORD` exported). Expected: a compile failure, because `RecipePhoto` doesn't exist yet.
- [ ] **Step 5: Implement `RecipePhoto` and `RecipePhotoRepository`** as in the Interfaces block.
- [ ] **Step 6: Run the test again.** It fails with "relation recipe_photos does not exist" until V3 is applied to the live DB. Ask the controller: either the user's backend restarts (devtools picks up V3), or the controller applies V3 after the user confirms. Then expected: PASS, 2 tests.
- [ ] **Step 7: Commit:** `feat(backend): recipe_photos table, entity and repository`. Include the storage SQL.

### Task 2: Photo path validation and public URLs (backend)

**Files:**
- Create: `RecipeAppBackend/src/main/java/mk/ukim/finki/wp/recipeappbackend/photo/RecipePhotoPaths.java`
- Create: `RecipeAppBackend/src/main/java/mk/ukim/finki/wp/recipeappbackend/photo/InvalidPhotoException.java`
- Modify: `RecipeAppBackend/src/main/java/mk/ukim/finki/wp/recipeappbackend/exception/GlobalExceptionHandler.java` (add a handler next to `handleInvalidCuisine`)
- Test: `RecipeAppBackend/src/test/java/mk/ukim/finki/wp/recipeappbackend/photo/RecipePhotoPathsTest.java`, plus a case in `GlobalExceptionHandlerTest`

**Interfaces:**
- Produces:
  - `@Component class RecipePhotoPaths` with:
    - Constructor `RecipePhotoPaths(@Value("${app.storage.photos-public-base-url}") String publicBaseUrl)`. Strip any trailing `/` from the base.
    - `String publicUrl(String path)`, which returns `base + "/" + path`.
    - `List<String> validate(UUID userId, List<String> paths)`. It returns an immutable copy in the same order. A null input counts as empty. It throws `InvalidPhotoException` with these messages:
      - more than 6: `"At most 6 photos"`;
      - duplicates: `"Duplicate photo: <path>"`;
      - a regex mismatch: `"Invalid photo: <path>"`.
  - `class InvalidPhotoException extends RuntimeException` (constructor taking a message).
  - The handler returns a 400 ProblemDetail "Validation failed" with `errors = Map.of("photos", ex.getMessage())`.

- [ ] **Step 1: Write the failing tests** in `RecipePhotoPathsTest`. Build `new RecipePhotoPaths("https://x.supabase.co/storage/v1/object/public/recipe-photos/")`, then use `U = UUID.randomUUID()` and `P = U + "/" + UUID.randomUUID() + ".webp"`.
  - `acceptsOwnPathsInOrder`: validating `[P1, P2]` returns `[P1, P2]`.
  - `nullIsEmpty`: `validate(U, null)` returns `[]`.
  - `rejectsOtherUsersFolder`, `rejectsTraversal` (`U + "/../x.webp"`), `rejectsFullUrl` (`"https://evil.com/a.jpg"`), `rejectsWrongExtension` (`.gif`) and `rejectsMissingUuidName` (`U + "/cat.jpg"`): each throws, with a message starting `"Invalid photo: "`.
  - `rejectsDuplicates`: the message is `"Duplicate photo: " + P`.
  - `rejectsSeven`: the message is `"At most 6 photos"`.
  - `publicUrlJoinsWithSingleSlash`: `publicUrl("a/b.jpg")` equals `"https://x.supabase.co/storage/v1/object/public/recipe-photos/a/b.jpg"`.
  - In `GlobalExceptionHandlerTest`: an `InvalidPhotoException("At most 6 photos")` maps to status 400 with `errors.photos == "At most 6 photos"`.
- [ ] **Step 2: Run them to verify they fail.** Run: `./mvnw -q test -Dtest='RecipePhotoPathsTest,GlobalExceptionHandlerTest'`. Expected: a compile failure.
- [ ] **Step 3: Implement** per the Interfaces block. Build the regex per call with `Pattern.quote(userId.toString())`.
- [ ] **Step 4: Run them to verify they pass.** Same command. Expected: all pass.
- [ ] **Step 5: Commit:** `feat(backend): validate recipe photo paths against the caller's folder`.

### Task 3: Photos in create, update and read; source fields removed (backend)

**Files:**
- Modify: `.../model/createDTO/RecipeCreateDto.java`
  - Remove `image`, `sourceName` and `sourceUrl`.
  - Add `@Size(max = 6) List<@NotBlank @Size(max = 200) String> photos`.
  - Update `toEntity` so it no longer sets those three fields.
- Modify: `.../model/displayDTO/RecipeDisplayDto.java`: add `List<String> photos` after `image`, and add a `List<String> photos` parameter to `fromEntity`.
- Modify: `.../service/RecipeService.java`: inject `RecipePhotoRepository` and `RecipePhotoPaths`; change create, update, `toDisplayDto` and `assemblePage`.
- Modify: `RecipeAppBackend/src/main/resources/application.properties`. Add only the line `app.storage.photos-public-base-url=https://bwnbvtbhcmeifsmlvmgl.supabase.co/storage/v1/object/public/recipe-photos`, staged with a trimmed patch and `git apply --cached` (see Global Constraints).
- Modify (constructor fallout): `RecipeControllerTest.java`, `RecipeServiceBrowseTest.java` and `RecipeServiceCuisineValidationTest.java`.
- Test: create `.../service/RecipeServicePhotosTest.java` (Mockito, in the style of `RecipeServiceCuisineValidationTest`).

**Interfaces:**
- Consumes: from Task 1, `RecipePhotoRepository.{findByRecipeIdOrderByPositionAsc, findByRecipeIdInOrderByPositionAsc, deleteAllByRecipeId, existsByRecipeId}`; from Task 2, `RecipePhotoPaths.{validate, publicUrl}` and `InvalidPhotoException`.
- Produces:
  - JSON `photos: string[]` (public URLs, in position order) on every `RecipeDisplayDto`, both single and list.
  - Request JSON `photos: string[]` of storage paths.

**Rules:**
- **create:**
  - Validate photos with `paths.validate(userId, dto.photos())` before saving.
  - Save the recipe, then save `RecipePhoto` rows at positions 0..n-1.
  - Set `image` to `publicUrl(photos[0])`, or null when there are none.
  - `sourceName` and `sourceUrl` stay null.
- **update:**
  - Order: `assertOwnedBy`, then validate cuisines and photos, then mutate.
  - Always set `sourceName` and `sourceUrl` to null.
  - If the validated list is empty and `!existsByRecipeId(recipeId)`, leave `image` unchanged (Review Focus 3). Otherwise:
    - call `deleteAllByRecipeId(recipeId)`;
    - save the new rows;
    - set `image` to the cover URL, or null.
- **display:**
  - `photos` = the URLs of the rows in order.
  - If there are no rows and `image != null`: `List.of(image)`.
  - Otherwise: `List.of()`.
  - `assemblePage` loads the rows with ONE `findByRecipeIdInOrderByPositionAsc(ids)` call, grouped like the ingredients are. The query count per page grows by exactly one.

- [ ] **Step 1: Write the failing tests** in `RecipeServicePhotosTest`.
  - `createStoresPhotosInOrderAndSetsCover`:
    - The saved `RecipePhoto` rows have positions 0 and 1 and paths `[P1, P2]`.
    - The saved recipe's `getImage()` equals `paths.publicUrl(P1)`.
    - The returned `photos` equals the URLs of `[P1, P2]`.
  - `createLeavesSourceFieldsNull`: the saved recipe has `getSourceName() == null` and `getSourceUrl() == null`.
  - `createRejectsForeignPath`: throws `InvalidPhotoException`, and `recipeRepository.save` is never called.
  - `updateReplacesPhotos`: `deleteAllByRecipeId(id)` is called before the new rows are saved (Mockito `inOrder`), and `image` becomes the new cover.
  - `updateWithEmptyListClearsImageWhenRecipeHadPhotos`: with `existsByRecipeId` true, `image` ends up null.
  - `updateKeepsLegacyLinkedImage`: with `existsByRecipeId` false, empty photos and image `"https://old.example/p.jpg"`, the image is unchanged and `deleteAllByRecipeId` is never called.
  - `updateByNonOwnerIs403BeforePhotoValidation`: called with an invalid path, it throws `ForbiddenOperationException`.
  - `displayFallsBackToImageForImportedRecipe`: no rows plus image X gives `photos == [X]`. No rows and no image gives `[]`.
  - `browseLoadsPhotosInOneBatch`: over a page of 2 recipes, `findByRecipeIdInOrderByPositionAsc` is called exactly once, and `findByRecipeIdOrderByPositionAsc` never.
  - `RecipeControllerTest`: `GET /api/recipes/{id}` returns `$.photos[0]` equal to the sample URL.
- [ ] **Step 2: Run them to verify they fail.** Run: `./mvnw -q test -Dtest='RecipeServicePhotosTest,RecipeControllerTest'`. Expected: a compile failure.
- [ ] **Step 3: Implement** the DTO, service and property changes per the Rules above. Fix the constructor calls in the three existing test classes: pass the mocks or a real `RecipePhotoPaths("https://x/recipe-photos")`.
- [ ] **Step 4: Run the regression list to verify it passes.** Run: `./mvnw -q test -Dtest='RecipeServicePhotosTest,RecipeControllerTest,RecipeServiceBrowseTest,RecipeServiceCuisineValidationTest,RecipePhotoPathsTest,GlobalExceptionHandlerTest,CommentControllerTest,RatingControllerTest,ReactionControllerTest,UserControllerTest,CuisineControllerTest,CuisineServiceTest,CuisineCatalogTest,SpoonacularRecipeTranslatorTest,SpoonacularImportServiceTest,SpoonacularImportRunnerTest,SpoonacularPropertiesTest,SpoonacularClientTest,CurrentUserIdArgumentResolverTest,TextArrayFunctionsTest'`, then the DB tests `-Dtest='*DbTest'`. Expected: all pass. Read the surefire XML, because `-q` hides success output.
- [ ] **Step 5: Commit** in two commits:
  - `feat(backend): recipe photos in create, update and read; drop user source fields`
  - the properties line on its own, staged via `git apply --cached`.

### Task 4: Frontend photo API, image shrinking and types

**Files:**
- Create: `recipe-app-frontend/src/api/photos.ts`
- Create: `recipe-app-frontend/src/lib/resizeImage.ts`
- Modify: `recipe-app-frontend/src/auth/supabaseClient.ts` (comment only: "Auth and photo storage only")
- Modify: `recipe-app-frontend/src/lib/types.ts`:
  - `RecipeDisplay` gains `photos: string[]`.
  - `RecipeCreate` drops `image`, `sourceName` and `sourceUrl`, and gains `photos: string[]`.
- Modify: `recipe-app-frontend/src/test/fixtures.ts` (`recipeFixture` gains `photos: []`)
- Test: `recipe-app-frontend/src/lib/resizeImage.test.ts` and `recipe-app-frontend/src/api/photos.test.ts`

**Interfaces:**
- Produces (`api/photos.ts`):
  - `PHOTO_BUCKET = 'recipe-photos'`
  - `MAX_PHOTOS = 6`
  - `MAX_PHOTO_BYTES = 5_242_880`
  - `ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp']`
  - `publicPhotoUrl(path: string): string`, which is `${import.meta.env.VITE_SUPABASE_URL}/storage/v1/object/public/recipe-photos/${path}`.
  - `pathFromPublicUrl(url: string): string | null`. It returns null when the URL isn't under that prefix.
  - `uploadPhoto(userId: string, blob: Blob, ext: 'jpg' | 'png' | 'webp'): Promise<string>`. It uploads to `${userId}/${crypto.randomUUID()}.${ext}` with `{ contentType: blob.type, upsert: false }`, returns the path, and throws an `Error` with the storage message if the upload fails.
  - `deletePhotos(paths: string[]): Promise<void>`. It does nothing for an empty list. On failure it logs with `console.warn` and never throws.
- Produces (`lib/resizeImage.ts`):
  - `targetSize(width: number, height: number, max = 2000): { width: number; height: number }`. It scales proportionally and never upscales. Round the results.
  - `prepareImage(file: File, deps?: { decode?: (f: File) => Promise<ImageBitmap | HTMLImageElement>; encode?: (src, w, h, type: string, quality: number) => Promise<Blob | null> }): Promise<{ blob: Blob; ext: 'webp' | 'jpg' }>`.
    - It decodes, sizes with `targetSize`, and encodes `image/webp` at 0.85.
    - If WebP encoding returns null, or returns a blob whose `type !== 'image/webp'`, it encodes `image/jpeg` at 0.85 instead.
    - It throws `Error("Couldn't read this image")` when decoding fails, and `Error("Photo is too large even after resizing")` when the result is over `MAX_PHOTO_BYTES`.
    - The defaults use `createImageBitmap` and a canvas `toBlob`.

- [ ] **Step 1: Write the failing tests.**
  - `targetSize(4000, 3000)` gives `{2000, 1500}`, and `targetSize(800, 600)` gives `{800, 600}`.
  - `prepareImage` with a fake `encode` that returns null for WebP returns `ext 'jpg'`.
  - A rejecting `decode` throws "Couldn't read this image".
  - An encode result over 5 MB throws the too-large message.
  - `pathFromPublicUrl(publicPhotoUrl('u/x.webp'))` gives `'u/x.webp'`, and `pathFromPublicUrl('https://img.spoonacular.com/a.jpg')` gives `null`.
  - With `vi.mock('@/auth/supabaseClient')` mocking `storage.from().upload`, `uploadPhoto('u', blob, 'webp')` resolves to a path matching `/^u\/[0-9a-f-]{36}\.webp$/`.
- [ ] **Step 2: Run them to verify they fail.** Run: `npx vitest run src/lib/resizeImage.test.ts src/api/photos.test.ts`. Expected: failures, because the modules are missing.
- [ ] **Step 3: Implement** per the Interfaces block. Fix any type errors the `types.ts` change causes in `RecipeFormPage` by temporarily sending `photos: []`. Task 6 does the real wiring.
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: all green.
- [ ] **Step 5: Commit:** `feat(frontend): photo storage API and in-browser image shrinking`.

### Task 5: PhotoPicker component (frontend)

**Files:**
- Create: `recipe-app-frontend/src/features/authoring/PhotoPicker.tsx`
- Test: `recipe-app-frontend/src/features/authoring/PhotoPicker.test.tsx`

**Interfaces:**
- Consumes: from Task 4, `uploadPhoto`, `prepareImage`, `publicPhotoUrl`, `MAX_PHOTOS` and `ACCEPTED_TYPES`.
- Produces: `PhotoPicker({ value, onChange, userId, onBusyChange }: { value: string[]; onChange: (paths: string[]) => void; userId: string; onBusyChange: (busy: boolean) => void })`.
  - `value` holds the uploaded storage paths in order.
  - `onBusyChange(true)` fires while any tile is uploading or in an error state.
  - The component keeps the pending and error tiles in local state. Only finished paths go out through `onChange`.

**UI:**
- A section labelled "Photos".
- The button "Add photos" wraps a hidden `<input type="file" multiple accept="image/jpeg,image/png,image/webp">` labelled "Add photos".
- Tiles sit in a grid: 3 columns on phones, 6 from `sm` up.
- Each tile:
  - a thumbnail (the object URL while pending, `publicPhotoUrl(path)` when done);
  - a pending `Spinner`;
  - buttons "Remove photo N", "Move photo N left" and "Move photo N right" (Move is disabled at the ends);
  - a "Cover" `Pill` on tile 1;
  - in the error state, the message plus "Retry" and "Remove".
- Messages use `role="alert"`:
  - "You can add up to 6 photos."
  - "Only JPEG, PNG or WebP photos."
- When all 6 slots are used, the Add button is disabled.

- [ ] **Step 1: Write the failing tests.** Use `vi.mock('@/api/photos')` and `vi.mock('@/lib/resizeImage')`, and drive the input with `userEvent.upload`.
  - `uploadsSelectedFilesInOrder`: two files make `onChange` receive `['u/1.webp', 'u/2.webp']`, and `uploadPhoto` is called with `'u'`.
  - `refusesFilesBeyondSix` (Review Focus 5): starting from a `value` of 4 and selecting 3 files, only 2 uploads happen and the alert says "You can add up to 6 photos."
  - `refusesWrongType`: a `.gif` gives the alert "Only JPEG, PNG or WebP photos." and no upload.
  - `removeAndMove`: with a value of 3, "Move photo 3 left" makes `onChange` reorder to `[a, c, b]`, and "Remove photo 1" gives `[b, c]`.
  - `coverPillOnFirst`: the "Cover" pill sits inside tile 1.
  - `busyWhilePendingAndOnError` (Review Focus 4): `onBusyChange(true)` fires while the upload promise is unresolved.
    - When `prepareImage` rejects with "Couldn't read this image", the tile shows that text plus "Retry", and busy stays true.
    - "Remove" on the failed tile sets busy back to false.
  - `expectNoEmoji()`.
- [ ] **Step 2: Run it to verify it fails.** Run: `npx vitest run src/features/authoring/PhotoPicker.test.tsx`. Expected: a failure, because the module is missing.
- [ ] **Step 3: Implement** per the Interfaces block and the UI notes.
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(frontend): photo picker with upload, reorder and cover`.

### Task 6: Recipe form and delete wiring (frontend)

**Files:**
- Modify: `recipe-app-frontend/src/features/authoring/RecipeFormPage.tsx`
  - Remove `image`, `sourceName` and `sourceUrl` from the zod schema, the defaults, `toDto`, the prefill and the JSX (the Image URL field and preview, Source name, Source URL).
  - Add `photos: z.array(z.string()).max(6)` and a `PhotoPicker` through `Controller`.
  - Disable Save while busy, with the hint "Waiting for photos to finish uploading".
- Modify: `recipe-app-frontend/src/features/recipe/RecipeDetailPage.tsx`: after a successful `deleteRecipe`, call `deletePhotos(recipe.photos.map(pathFromPublicUrl).filter(Boolean))`.
- Test: `recipe-app-frontend/src/features/authoring/authoring.test.tsx` and `recipe-app-frontend/src/features/recipe/detail.test.tsx`

**Interfaces:**
- Consumes: from Task 5, `PhotoPicker`; from Task 4, `deletePhotos`, `pathFromPublicUrl` and `RecipeCreate.photos`.
- Prefill on edit: `photos = recipe.photos.map(pathFromPublicUrl).filter(nonNull)`. Non-bucket URLs are dropped from the tiles (Review Focus 3).
- After a successful update: `deletePhotos(initialPaths minus submittedPaths)`.
- `userId` for the picker comes from `useAuth().profile.id`.

- [ ] **Step 1: Write the failing tests** in `authoring.test.tsx`, mocking `@/api/photos`.
  - `noSourceOrImageUrlFields`: no fields labelled "Source name", "Source URL" or "Image URL".
  - `createBodyCarriesPhotosNotSourceFields`: the captured POST body has a `photos` array and no keys `image`, `sourceName` or `sourceUrl`.
  - `editPrefillsBucketPhotosOnly`: the recipe has `photos = [publicPhotoUrl('u-1/a.webp'), 'https://img.spoonacular.com/x.jpg']`, and exactly 1 tile renders.
  - `removingPhotoOnEditDeletesFileAfterSave`: remove tile 1, then Save. The PUT body has `photos: []`, and `deletePhotos` is called with `['u-1/a.webp']` after the PUT resolves.
  - `saveDisabledWhileUploading`: while a mocked upload is unresolved, the Save button is disabled.
  - In `detail.test.tsx`, `deletingRecipeDeletesItsPhotos`: after the confirm-dialog delete, `deletePhotos` is called with the recipe's bucket paths.
- [ ] **Step 2: Run them to verify they fail.** Run: `npx vitest run src/features/authoring src/features/recipe`. Expected: failures.
- [ ] **Step 3: Implement** per the Files and Interfaces notes. Update existing tests that typed into the removed fields.
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(frontend): photos in the recipe form; remove source and image URL fields`.

### Task 7: Photo collage and viewer on the recipe page (frontend)

**Files:**
- Create: `recipe-app-frontend/src/features/recipe/PhotoCollage.tsx` (exports `PhotoCollage` and `PhotoViewer`)
- Modify: `recipe-app-frontend/src/features/recipe/RecipeDetailPage.tsx`: replace the single `<img>` and placeholder block at about lines 104-110 with `<PhotoCollage photos={recipe.photos} title={recipe.title} />`, keeping the existing placeholder for 0 photos.
- Test: `recipe-app-frontend/src/features/recipe/PhotoCollage.test.tsx`

**Interfaces:**
- Produces:
  - `PhotoCollage({ photos, title }: { photos: string[]; title: string })`.
  - `PhotoViewer({ photos, title, index, onIndexChange, open, onOpenChange })`.
- Layout and viewer behaviour are exactly as in spec §6. Tiles are `<button aria-label="Open photo N of M">` containing `<img alt="{title}, photo N of M">`. From `sm` up, the "+N" overlay sits on the third tile when there are more than 3 photos.
  - Below `sm`, the phone layout is a full-width cover with a row of thumbnails of the rest.
  - Both layouts may render in the DOM with responsive `hidden`/`sm:grid` classes. If so, tests query within `data-testid="collage-desktop"`.
- The viewer is a Radix Dialog. It has a title (sr-only), "Previous photo" and "Next photo" buttons (hidden when there's 1 photo), "Close", a counter "N / M", and ArrowLeft/ArrowRight handling on the content. Its backdrop is `bg-olive/80`.

- [ ] **Step 1: Write the failing tests.**
  - `renders{1,2,3,6}Photos`: within the desktop layout, the visible tile count is 1, 2, 3 and 3 respectively. With 6, the third tile contains the text "+3".
  - `altTexts`: `getByAltText('Pasta, photo 2 of 3')` is found.
  - `viewerOpensOnClickedPhoto`: clicking "Open photo 2 of 3" shows the dialog with counter "2 / 3".
  - `viewerNavigates`: "Next photo" shows "3 / 3", ArrowLeft shows "2 / 3", and Escape closes the dialog.
  - `singlePhotoHasNoArrows`: with 1 photo, there's no "Next photo" button.
  - `expectNoEmoji()`.
- [ ] **Step 2: Run it to verify it fails.** Run: `npx vitest run src/features/recipe/PhotoCollage.test.tsx`. Expected: a failure.
- [ ] **Step 3: Implement** per spec §6 and Golden Hour tokens (`--radius-card`, `ring-sand`, `object-cover`).
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(frontend): recipe photo collage with full-screen viewer`.

### Task 8: Rollout, verification and dev log

**Files:**
- Modify: `docs/plateful-frontend-dev-log.txt`. Append "PART 3 — PHOTO COLLAGE", with one section per task above, in the existing plain-text style: what was done, how, and the key code explained.

- [ ] **Step 1: The controller asks the user** to run `docs/supabase/recipe-photos-storage.sql` in the Supabase SQL editor, or to confirm that the controller may run it over JDBC. Never run it without that confirmation.
- [ ] **Step 2: Verify everything is green.** The Task 3 backend regression list plus `*DbTest` all pass, and frontend `npx vitest run` plus `npm run build` are green.
- [ ] **Step 3: Live smoke check on the user's backend.** `GET /api/recipes/{any id}` returns a `photos` array. A `POST` with a foreign path returns 400 with `errors.photos`. This needs a signed-in user, so the user checks it in the browser.
- [ ] **Step 4: Write the dev log section and commit:** `docs: dev log for the photo collage`. Push.
