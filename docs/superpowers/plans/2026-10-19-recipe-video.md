# Recipe Video Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Authors can attach one short video to a recipe (up to 60 s and 50 MB). It autoplays muted in the collage and plays with sound in the viewer.

**Architecture:**
- **Upload:** the browser test-plays the file, captures a JPEG still frame, and uploads both straight to a new public Supabase bucket, `recipe-videos`, in the author's own folder.
- **Backend:** stores only the two paths and the duration (Flyway V7). It validates them like photo paths and returns public URLs.
- **Review:** a video change rides the existing revision bump into admin review.
- **Display:** `PhotoCollage` and `PhotoViewer` gain a video tile at position 2.

**Tech Stack:**
- Spring Boot 4 / Java 21 / Flyway / Postgres (Supabase);
- React 19 / TS / TanStack Query / Vitest + RTL + MSW;
- browser `<video>`, canvas and IntersectionObserver. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-19-recipe-video-design.md`

## Global Constraints

### Limits
- Video limits: `MAX_VIDEO_BYTES = 52_428_800`, `MAX_VIDEO_SECONDS = 60`.
- Accepted types: `video/mp4`, `video/webm`, `video/quicktime`.
- Bucket: `recipe-videos`, with MIME types `video/mp4`, `video/webm`, `video/quicktime` and `image/jpeg`, and a 50 MB file limit.

### Paths
- Clip: `{userId}/{uuid}.{mp4|webm|mov}`.
- Poster: `{userId}/{uuid}.jpg`.

### API
- **Request:** `RecipeCreateDto.video` = `{ path, posterPath, durationSeconds } | null`.
- **Response:** `RecipeDisplayDto.video` = `{ url, posterUrl, durationSeconds } | null`.
- **Validation errors:** 400 with `errors.video` for an invalid video.

### Exact copy
- **Form:**
  - field label: "Video (optional)"; button: "Add video";
  - "Videos must be MP4, WebM or MOV, up to 50 MB."
  - "This video can't be played in most browsers. Please export it as MP4 and try again."
  - "Videos can be up to 60 seconds long."
  - "Uploading video…"
  - "Couldn't upload the video. Please try again."
  - buttons: "Play", "Replace", "Remove".
- **Backend 400 messages:**
  - "Video must be an uploaded file in your folder."
  - "Video poster must be an uploaded image in your folder."
  - "Videos can be up to 60 seconds long."
- **Collage:**
  - the tile's label: "Play video, {n} seconds";
  - the duration chip uses `m:ss`;
  - the viewer's error: "This video couldn't be played."
- **Card badge:** "Video".

### Autoplay
- The collage tile's `<video>` has `muted`, `loop`, `playsInline`, `preload="none"` and `poster`.
- It plays when 50% or more is visible (IntersectionObserver `threshold: 0.5`) and pauses when it isn't.
- It never autoplays when `prefers-reduced-motion: reduce` matches.
- A rejected `play()` is swallowed.
- The viewer's `<video>` has `controls`, `autoPlay` and `playsInline`, with no loop and sound on. It pauses on navigate and on close.
- Cards never autoplay.

### UI rules
Golden Hour tokens, no emojis (`expectNoEmoji()`), and real buttons.

### Safety, BEFORE any backend compile

    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'RecipeAppBackendApplication' } | ForEach-Object { \$_.CommandLine -match 'spoonacular' }"

If that prints True, STOP and report BLOCKED.

### Backend tests
- Set `export JAVA_HOME="$USERPROFILE/.jdks/ms-21.0.9"`, then run `./mvnw -q test -Dtest=<Classes>` from `RecipeAppBackend/`. NEVER run unfiltered.
- Clear `target/surefire-reports` before counting.

### DB tests
- Use EXACTLY the annotations of `RecipeCuisineQueryDbTest`, and write only inside the rolled-back transaction.
- Export the password without printing it, from `RecipeAppBackend/` (the file is `RecipeAppBackend/.idea/workspace.xml`):

      export SUPABASE_DB_PASSWORD="$(python -c "import re;s=open('.idea/workspace.xml',encoding='utf-8').read();m=re.search(r'name=\"SUPABASE_DB_PASSWORD\" value=\"([^\"]*)\"',s);print(m.group(1) if m else '')")"

- DB tests run with Flyway disabled. V7 reaches the live DB when the user's running backend restarts after a compile. Never apply migration SQL by hand.

### Backend regression list
Add each task's new classes to it:

    RecipeServicePhotosTest,RecipeControllerTest,RecipeServiceBrowseTest,RecipeServiceCuisineValidationTest,RecipePhotoPathsTest,GlobalExceptionHandlerTest,CommentControllerTest,RatingControllerTest,ReactionControllerTest,UserControllerTest,CuisineControllerTest,CuisineServiceTest,CuisineCatalogTest,SeaCatalogTest,SearchWordsTest,CurrentUserIdArgumentResolverTest,TextArrayFunctionsTest,MfaFactorLookupTest,MfaEnforcementFilterTest,SavedRecipeServiceTest,SavedRecipeControllerTest,RecipeFilterTest,RecipeVisibilityTest,RecipeServiceReviewStatusTest,InteractionReviewGuardTest,AdminReviewServiceTest,AdminReviewControllerTest,AdminGuardTest,ReviewEmailsTest,ReviewEmailListenerTest,MailConfigTest,SmtpReviewEmailSenderTest,ReviewEmailTransactionTest

Then run `-Dtest='*DbTest'`.

### Frontend
- Run npm and npx through the Bash tool, from `recipe-app-frontend/`.
- `npx vitest run` and `npm run build` must be green before each commit.

### Commits
- End each message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Stage explicit paths only.
- Push after each task to `feature/recipe-video`.
- Never print secrets. Never edit `.idea/`.

## Review Focus

1. **An edit that only changes the video,** for example replacing it with a new clip, on an approved non-admin recipe. The revision increases and the recipe goes back to pending. Task 2: `videoOnlyEditGoesBackToReview`.
2. **The author removes the last photo but keeps the video.** The card image becomes the poster instead of going blank. Task 2: `removingLastPhotoFallsBackToPoster`.
3. **The author picks a second video while the first is still uploading.** The picker is disabled during the upload, so no two uploads race. Task 4: `pickerDisabledWhileUploading`.
4. **The author replaces a video and then cancels the edit.** The original's files are never deleted. Task 4: `cancelEditDeletesNothing`.
5. **The viewer is closed while the video is playing.** Playback stops, with no background audio. Task 5: `closingViewerPausesVideo`.

---

### Task 1: Storage setup, schema, DTOs and video validation (backend)

**Files:**
- Create:
  - `docs/supabase/recipe-videos-storage.sql`
  - `RecipeAppBackend/src/main/resources/db/migration/V7__recipe_video.sql`
  - `.../photo/RecipeVideoPaths.java`
  - `.../photo/InvalidVideoException.java`
  - `.../model/createDTO/RecipeVideoCreateDto.java`
  - `.../model/displayDTO/RecipeVideoDto.java`
  - Tests: `.../photo/RecipeVideoPathsTest.java`, `.../repository/RecipeVideoSchemaDbTest.java`
- Modify:
  - `.../model/entities/Recipe.java`
  - `.../model/createDTO/RecipeCreateDto.java`
  - `.../model/displayDTO/RecipeDisplayDto.java`
  - `.../exception/GlobalExceptionHandler.java`
  - `src/main/resources/application.properties`
  - Tests: `RecipeControllerTest`, `GlobalExceptionHandlerTest`

(`...` = `RecipeAppBackend/src/main/java/mk/ukim/finki/wp/recipeappbackend` or the matching test path.)

**Interfaces:**
- **Produces:**
  - **`Recipe` fields:** `videoPath`, `videoPosterPath` and `videoDurationSeconds` (`Integer`, mapped to SMALLINT), each with a getter and a setter.
  - **`record RecipeVideoCreateDto(@NotBlank @Size(max=200) String path, @NotBlank @Size(max=200) String posterPath, @NotNull Integer durationSeconds)`.**
  - **`RecipeCreateDto`:** gains a final component, `@Valid RecipeVideoCreateDto video`, which is nullable.
  - **`record RecipeVideoDto(String url, String posterUrl, int durationSeconds)`.**
  - **`RecipeDisplayDto`:** gains the component `RecipeVideoDto video`, placed before `review`. `fromEntity(...)` gets one more parameter, `RecipeVideoDto video`, and `withReview` copies it.
  - **`RecipeVideoPaths` (`@Component`):**
    - the constructor takes `@Value("${app.storage.videos-public-base-url}")`;
    - `String publicUrl(String path)`;
    - `RecipeVideoCreateDto validate(UUID userId, RecipeVideoCreateDto video)` returns null for a null input, otherwise the input itself, and throws `InvalidVideoException(message)` with the spec §4.3 messages;
    - `RecipeVideoDto toDisplay(Recipe r)` returns null when `videoPath` is null.
  - **`InvalidVideoException`:** mapped to 400 with `errors: { video: message }`, in the same style as `InvalidPhotoException`.
  - **Property:** `app.storage.videos-public-base-url=https://bwnbvtbhcmeifsmlvmgl.supabase.co/storage/v1/object/public/recipe-videos`.

Steps:

- [ ] **Step 1: Write `recipe-videos-storage.sql`** by mirroring `docs/supabase/recipe-photos-storage.sql`, with the bucket values from Global Constraints and the same three own-folder policies renamed for `recipe-videos`.
- [ ] **Step 2: Write V7 verbatim from spec §4.1.** Confirm first that V6 is the latest migration.
- [ ] **Step 3: Write the failing tests.**
  - `RecipeVideoPathsTest`:
    - **`acceptsOwnFolderVideoAndPoster`** (mp4, webm, mov);
    - **`rejectsForeignFolder`** (message: "Video must be an uploaded file in your folder.");
    - **`rejectsBadExtension`** (`.avi`);
    - **`rejectsNonJpegPoster`** (`.png`; message: "Video poster must be an uploaded image in your folder.");
    - **`rejectsMalformedUuid`**;
    - **`durationBounds`**: 0 and 61 are rejected with "Videos can be up to 60 seconds long.", 1 and 60 are accepted;
    - **`nullVideoIsNull`**;
    - **`publicUrlJoinsBase`**.
  - `RecipeVideoSchemaDbTest`:
    - **`existingRecipesHaveNoVideo`**: `count(*) where video_path is not null or video_poster_path is not null or video_duration_seconds is not null` and `created_at <= (installed_on of version '7')` gives 0. Guard it with a positive count of pre-V7 rows, so the scoped check can't pass on an empty scope.
    - **`partialVideoViolatesConstraint`**: a native update that sets only `video_path` on a recipe inside the transaction throws `DataIntegrityViolationException` or a `PersistenceException` subtype.
  - `RecipeControllerTest`: **`invalidVideoIs400`**, where a mocked service throws `InvalidVideoException("Video must be an uploaded file in your folder.")` and the test asserts `$.errors.video`.
- [ ] **Step 4: Run them to verify they fail.** Expected: a compile failure.
- [ ] **Step 5: Implement it.** Update every positional `RecipeDisplayDto` constructor call (main and tests) to add `null` for `video`. List them in the report.
- [ ] **Step 6: Run the regression list, the new classes and `*DbTest`.** Expected: green. The DB test needs V7 live; if it isn't, report NEEDS_CONTEXT.
- [ ] **Step 7: Commit:** `feat(backend): recipe video schema, dto and validation`.

### Task 2: The service stores, returns and reviews the video (backend)

**Files:**
- Modify: `.../service/RecipeService.java` (`create`, `update`, the display assembly, cover selection)
- Test: `.../service/RecipeServiceVideoTest.java` (new)

**Interfaces:**
- **Consumes:** Task 1's `RecipeVideoPaths.validate` / `toDisplay`, and the `Recipe` video fields.
- **Produces:**
  - `create` and `update` validate the video before any mutation, then set or clear the three fields.
  - Every `RecipeDisplayDto` the service builds carries `video` from `RecipeVideoPaths.toDisplay(recipe)`. That covers the detail view, the batched pages and the create/update responses.
  - `coverUrl` becomes `coverUrl(List<String> photos, RecipeVideoCreateDto video)`: `photos[0]` if there are photos, otherwise the poster URL if there's a video, otherwise null.
  - The legacy rule is unchanged: with no photo rows and no photos in the request, an imported recipe's linked image is left alone, unless a video is being set, in which case the poster wins.

Steps:

- [ ] **Step 1: Write the failing tests** (mocked repositories, the same style as `RecipeServicePhotosTest`).
  - **`createWithVideoStoresAndReturnsIt`:** the entity fields are set, and the DTO's `video.url` and `posterUrl` are public URLs, with `durationSeconds` 42.
  - **`updateAddsReplacesAndRemovesVideo`:** three calls; after the removal all three fields are null.
  - **`videoOnlyEditGoesBackToReview`:** a non-admin's approved recipe, with only the video changed, ends with `revision + 1` and status PENDING.
  - **`noPhotosWithVideoUsesPosterAsImage`.**
  - **`removingLastPhotoFallsBackToPoster`.**
  - **`invalidVideoLeavesRecipeUntouched`:** a foreign path throws `InvalidVideoException`, and the revision and fields are unchanged.
  - **`pageAssemblyCarriesVideo`:** `toDisplayPage` items include `video`.
- [ ] **Step 2: Run them to verify they fail.**
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run the regression list plus `RecipeServiceVideoTest`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(backend): recipes store and return their video`.

### Task 3: Frontend video API, probe helper and types

**Files:**
- Create: `recipe-app-frontend/src/api/videos.ts`, `src/lib/videoProbe.ts`
- Modify: `src/lib/types.ts`, `src/test/fixtures.ts` (`recipeFixture` gets `video: null`)
- Test: `src/api/videos.test.ts`

**Interfaces:**
- **Consumes:** the backend JSON from Tasks 1–2.
- **Produces:**
  - **`api/videos.ts`:**
    - `VIDEO_BUCKET`, `MAX_VIDEO_BYTES`, `MAX_VIDEO_SECONDS`, `ACCEPTED_VIDEO_TYPES`;
    - `publicVideoUrl(path: string): string`;
    - `pathFromPublicVideoUrl(url: string): string | null`, host-independent, the same as `pathFromPublicUrl`;
    - `uploadVideo(userId: string, file: File): Promise<string>`: the extension comes from the MIME type (mp4, webm or mov), `upsert: false`;
    - `uploadPoster(userId: string, blob: Blob): Promise<string>`;
    - `deleteVideoFiles(paths: string[]): Promise<void>`, best-effort with a warning, never throwing.
  - **`lib/videoProbe.ts`:**
    - `probeVideo(file: File): Promise<{ playable: true; durationSeconds: number } | { playable: false }>`
    - `capturePoster(file: File, atSeconds: number): Promise<Blob>`

    These follow spec §5.1 exactly: a 15 s timeout, rejecting a zero, infinite or NaN duration, the longest side at most 1280 px, and JPEG at quality 0.85. Revoke the object URLs.
  - **Types:**
    - `RecipeVideo = { url: string; posterUrl: string; durationSeconds: number }`;
    - `RecipeVideoInput = { path: string; posterPath: string; durationSeconds: number }`;
    - `RecipeDisplay.video?: RecipeVideo | null`;
    - `RecipeCreate.video?: RecipeVideoInput | null`.

Steps:

- [ ] **Step 1: Write the failing tests (`videos.test.ts`).** The supabase storage client is mocked, as in `photos.test.ts`.
  - **`uploadVideoUsesOwnFolderAndMimeExtension`:** `video/quicktime` gives `.mov`.
  - **`uploadPosterIsJpeg`.**
  - **`publicUrlRoundTrips`.**
  - **`pathFromForeignHostUrl`.**
  - **`deleteSwallowsErrors`.**

  `videoProbe` isn't unit-tested, because jsdom can't decode media. Keep it free of logic beyond the spec's rules, and exercise it in the manual browser check.
- [ ] **Step 2: Run them to verify they fail.**
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(frontend): video upload api and browser probe`.

### Task 4: The `VideoPicker` field in the recipe form

**Files:**
- Create: `src/features/authoring/VideoPicker.tsx`
- Modify: `src/features/authoring/RecipeFormPage.tsx` (the zod schema, the defaults, edit prefill, submit, the busy state, post-save cleanup)
- Test: `src/features/authoring/videoPicker.test.tsx`

**Interfaces:**
- **Consumes:** Task 3's API and probe, with `vi.mock('@/lib/videoProbe')` in the tests. The viewer from Task 5 isn't available yet, so "Play" opens a minimal native `<video controls>` preview dialog. Task 5 may switch it to `PhotoViewer`.
- **Produces:**
  - **`<VideoPicker value={RecipeVideoInput | null} onChange={(v) => …} onBusyChange={(busy: boolean) => …} userId={string} />`.**
  - **Form value:** `video: RecipeVideoInput | null`. Edit prefill converts the recipe's `video` URLs back to paths with `pathFromPublicVideoUrl`; if either doesn't convert, the value is null.
  - **Save** is disabled while photos OR the video are busy.
  - **After a successful save,** `deleteVideoFiles` removes:
    - the original recipe's paths that aren't in the saved value;
    - paths uploaded in this session that aren't in the saved value.

Steps:

- [ ] **Step 1: Write the failing tests (`videoPicker.test.tsx`).** Use real `userEvent`, `upload()` on the hidden file input, a mocked probe, and MSW for create and update.
  - **`rejectsWrongTypeOrSize`:** the exact message.
  - **`rejectsUnplayable`:** the exact message.
  - **`rejectsTooLong`:** the stored duration is `Math.ceil(probe duration)`, and a clip is rejected when that is over 60. So `60.4` is rejected with the exact message, `60.0` is accepted as 60, and `0.4` is stored as 1.
  - **`pickerDisabledWhileUploading`:** with the upload held by a deferred promise, the field shows "Uploading video…" and the "Add video" control and the form's Save button are disabled.
  - **`successShowsPosterDurationAndActions`:** "0:42", "Play", "Replace" and "Remove".
  - **`uploadFailureShowsMessage`:** the exact message, and the orphaned half of the pair is deleted.
  - **`removeThenSaveSendsNullAndDeletesAfterSave`:** the PUT body has `video: null`, and `deleteVideoFiles` is called after the PUT resolves, never before.
  - **`cancelEditDeletesNothing`:** replace the video, then navigate away without saving; `deleteVideoFiles` is never called with the original paths.
  - **`createSendsVideo`:** the POST body has `video` with the paths and `durationSeconds`.
  - `expectNoEmoji()`.
- [ ] **Step 2: Run them to verify they fail.**
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green. List any adjusted authoring tests.
- [ ] **Step 5: Commit:** `feat(frontend): add a video to a recipe`.

### Task 5: Collage tile with autoplay, viewer playback and card badge

**Files:**
- Modify:
  - `src/features/recipe/PhotoCollage.tsx` (the `PhotoCollage` and `PhotoViewer` media list)
  - `src/features/recipe/RecipeBody.tsx` (pass `video`)
  - `src/components/RecipeCard.tsx` (the badge)
  - optionally `src/features/authoring/VideoPicker.tsx` (switch "Play" to `PhotoViewer`)
- Create: `src/features/recipe/VideoTile.tsx`
- Test: `src/features/recipe/videoCollage.test.tsx`

**Interfaces:**
- **Consumes:** `RecipeDisplay.video` (Task 3 types).
- **Produces:**
  - **`PhotoCollage`:** `{ photos, video, title }` builds a media list. With photos, the order is `[photo0, video, ...rest]`. With no photos, it's `[video]`. The existing wide layout, mobile layout and "+N" logic count the video as a tile.
  - **`VideoTile`:**
    - a `<button aria-label="Play video, {n} seconds">` holding the autoplay `<video>` described in Global Constraints;
    - a play icon;
    - an `m:ss` chip.
    - `IntersectionObserver` uses `threshold: 0.5`. When it's missing (an old browser, or SSR), the tile doesn't autoplay.
  - **`PhotoViewer`:**
    - takes the same media list;
    - at a video item, renders the viewer `<video>` from Global Constraints;
    - pauses it on index change and on close (an effect cleanup plus a ref);
    - shows the error copy on the video's `error` event.
  - **`RecipeCard`:** a top-left badge with a camera icon (lucide `Video`) and the text "Video" when `recipe.video` is set.

Steps:

- [ ] **Step 1: Write the failing tests (`videoCollage.test.tsx`).** Mock `IntersectionObserver` with a controllable fake. Stub `HTMLMediaElement.prototype.play` (returning a resolved promise) and `pause` with spies. Mock `matchMedia`.
  - **`videoIsSecondTile`.**
  - **`videoIsCoverWithoutPhotos`.**
  - **`tileLabelAndDuration`:** "Play video, 42 seconds" and "0:42".
  - **`tileVideoAttributes`:** `muted`, `loop`, `playsInline` and `preload="none"`.
  - **`autoplaysWhenVisiblePausesWhenNot`.**
  - **`noAutoplayWithReducedMotion`.**
  - **`rejectedPlayIsSwallowed`:** `play()` rejects, with no error output and the poster still shown.
  - **`viewerArrowOrder`:** photo 1, then the video, then photo 2.
  - **`viewerVideoHasControlsAndSound`:** `controls` is set and `muted` is false.
  - **`closingViewerPausesVideo`.**
  - **`navigatingAwayPausesVideo`.**
  - **`viewerShowsErrorOnFailure`.**
  - **`cardShowsVideoBadge`:** shown with a video, absent without.
  - `expectNoEmoji()`.
- [ ] **Step 2: Run them to verify they fail.**
- [ ] **Step 3: Implement it.** Keep the existing `PhotoCollage` tests passing unchanged. Photo-only recipes must render exactly as before.
- [ ] **Step 4: Run `npx vitest run` and `npm run build`.** Expected: green.
- [ ] **Step 5: Commit:** `feat(frontend): video tile with autoplay and viewer playback`.

### Task 6: Legal wording, verification and dev log

**Files:**
- Modify:
  - `src/features/content/PrivacyPage.tsx`
  - `src/features/content/TermsPage.tsx`
  - `src/features/content/content.test.tsx`
  - `docs/plateful-frontend-dev-log.txt`

Steps:

- [ ] **Step 1: Write the failing assertion in `content.test.tsx`.** Privacy contains "photos and videos" in the content-you-post line and in the visibility line, and "photo and video storage" in the Supabase line. Terms contains "photos or videos" in the copied-content sentence.
- [ ] **Step 2: Update the four phrases** listed in spec §5.4. Run `npx vitest run src/features/content`. Expected: green.
- [ ] **Step 3: Run everything.**
  1. The safety check.
  2. The full backend regression list plus `RecipeVideoPathsTest` and `RecipeServiceVideoTest`, then `*DbTest`.
  3. `npx vitest run` and `npm run build`.

  Report the counts from the surefire XML.
- [ ] **Step 4: Append "PART 14 — RECIPE VIDEO" to the dev log.**
  - Match the style of PARTs 12–13: plain text, at most 80 columns.
  - Write one section per task.
  - Add a Rollout section: run `docs/supabase/recipe-videos-storage.sql` once; V7 applies on restart.
  - Add the known limitations: no transcoding (HEVC from desktop browsers may be refused); unsaved-form uploads remain; storage photos and videos can be replaced at the same path.
- [ ] **Step 5: Commit** as `docs: legal wording and dev log for recipe video`, and push.
