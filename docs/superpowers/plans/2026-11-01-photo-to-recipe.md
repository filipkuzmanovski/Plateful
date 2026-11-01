# Photo to Recipe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On `/cook`, a signed-in user scans 1 to 3 kitchen photos with Groq's vision model, gets an ingredient list merged into the chips (conflicts with their preferences left out in code), edits it, and generates with the existing flow; photos are never stored.

**Architecture:** `AiRequest` gains optional images; Groq and Gemini add image parts; a second `FallbackRecipeAi` bean (`visionAi`, order `groq` only) has its own cooldowns. A new `PhotoScanService` in `generation` prepares photos in memory (JPEG check, size and pixel limits, scale and re-encode without metadata), reserves one of 10 daily scans (`ai_usage.scans`, Flyway V10), calls `visionAi`, normalises and filters the names, and refunds on any failure. The frontend shrinks photos in the browser and adds a `PhotoScan` panel to `CookPage`.

**Tech Stack:** Spring Boot 4, Java 21, `javax.imageio`, Spring JDBC, Flyway; React 19, TypeScript, TanStack Query, Vitest, React Testing Library, MSW.

**Spec:** `docs/superpowers/specs/2026-11-01-photo-to-recipe-design.md` (read it whole; this plan cites its sections).

## Global Constraints

- Branch `feature/photo-to-recipe` from `main`. Commit after every task; stage explicit paths only; messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never stage `tools/dataset/README.md` (someone else's uncommitted edit).
- **Before any backend compile**, run the Spoonacular process check from `docs/ai-features-handoff.md` §6; if it prints `True`, stop.
- Backend tests: `export JAVA_HOME="$USERPROFILE/.jdks/ms-21.0.9"`, from `RecipeAppBackend/`: `./mvnw -q test -Dtest=<Classes>`. **Never run unfiltered.** Clear `target/surefire-reports` first and read counts from it.
- DbTests: exactly `RecipeCuisineQueryDbTest`'s annotations (`@Tag("db")`, `@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")`, `@DataJpaTest`, `@AutoConfigureTestDatabase(replace = NONE)`, `@TestPropertySource(properties = "spring.flyway.enabled=false")`), **no `@Import`**, writes only inside the rolled-back transaction. Password exported without printing (handoff §6).
- Flyway is off in DbTests: V10 reaches Supabase only when the user restarts the backend. Never apply SQL by hand.
- Frontend, from `recipe-app-frontend/`: `npx vitest run` and `npm run build` both green before each frontend commit.
- No real AI call in any test. No emojis in UI; new screens' tests call `expectNoEmoji()` (`@/test/noEmoji`).
- Never log or put in exception messages: prompts, replies, keys, user ids, image bytes.
- Limits (spec): 1 to 3 photos; each 1 to 1,048,576 bytes; JPEG magic `FF D8 FF`; max 4,096 px per side (read before decode); output long side ≤ 1,536 px at quality 0.85; size guard total > 2,800,000 bytes → re-encode all at 0.7; 10 scans per user per UTC day; 45 s budget, retry only with ≥ 20 s left; ≤ 40 names in a reply, each 1 to 60 chars; ≤ 15 returned.
- Copy (exact): see spec §5.4 table and §6; reused verbatim in tasks below.
- Update the plain-text dev log `docs/plateful-frontend-dev-log.txt` with **PART 19** in the last task (max 80 columns, CRLF).
- Rulings go to the git-ignored ledger `.superpowers/sdd/2026-11-01-photo-to-recipe/progress.md`.

## Rulings made while planning (record in the ledger at start)

1. **Groq vision uses `json_object`, not strict `json_schema`.** Groq documents JSON mode with images; strict schema with images is undocumented (spec §4.1 allowed this fallback). The scan system text spells out the exact JSON shape; `ScanParser` validates.
2. **Groq vision sends `reasoning_effort: "none"` and `reasoning_format: "hidden"`** (Groq's reasoning docs for `qwen/qwen3.8-27b`: `none` turns reasoning off; JSON mode needs `parsed` or `hidden`, `raw` is a 400). The text path keeps `reasoning_effort: "low"` unchanged.
3. **The existing `photo.InvalidPhotoException`** (recipe photo paths, `errors.photos` shape) is unrelated; the scan uses a new `exception.InvalidScanPhotoException` → 400 `invalid_photo`.
4. **`leftOut` may hold one ingredient more than once** (one row per reason, as `SafetyCheck.conflicts` returns). The frontend groups by ingredient: "peanut butter (Peanuts, Vegan)". Reason labels: text after `"allergy: "` or `"diet: "`; `"dislike"` shows as "you dislike it".

## Review Focus

1. **A phone photo with EXIF orientation** (portrait iPhone shot): the browser applies orientation (`imageOrientation: 'from-image'`) before drawing, so the backend never sees a sideways image; a test stubs `createImageBitmap` and asserts that option is passed (Task 6).
2. **Scanning twice in a row with chips already at 15**: nothing is added, the "didn't fit" line lists all found names, and no chip is lost (Task 7).
3. **The scan endpoint hit without a `photos` part or with a non-file field** (another client): 400 `invalid_photo`, no reservation (Task 5, `scan_missingPart_is400InvalidPhoto`).
4. **A grayscale or CMYK JPEG** (some cameras and scanners): ImageIO may fail to decode CMYK; it must be a clean 400 `invalid_photo`, never a 500 (Task 3, `cmykJpeg_isInvalidNot500` — build with an `ImageWriter` if possible, else a truncated JPEG body after a valid header).
5. **Groq's 413 or a cooling-down vision model** must not cool down text recipes: a vision quota leaves `recipeAi` usable (Task 2, `visionCooldown_doesNotAffectTextChain`).

---

### Task 1: Scan counter (V10, repository, usage service, usage DTO)

**Files:**
- Create: `RecipeAppBackend/src/main/resources/db/migration/V10__ai_scans.sql`
- Modify: `RecipeAppBackend/src/main/java/mk/ukim/finki/wp/recipeappbackend/repository/AiStateRepository.java`
- Modify: `.../generation/AiUsageService.java`, `.../model/displayDTO/AiUsageDto.java`, `.../ai/AiProperties.java` (add `dailyScanLimit` only), `RecipeAppBackend/src/main/resources/application.properties`
- Test: `.../repository/AiStateRepositoryDbTest.java`, `.../generation/AiUsageServiceTest.java`, `.../ai/AiPropertiesTest.java`, `.../controller/AiRecipeControllerTest.java` (usage test)
- Frontend type only in Task 6.

**Interfaces:**
- Produces: `AiStateRepository.reserveScan(UUID userId, LocalDate day, int limit): OptionalInt`, `refundScan(UUID, LocalDate): void`, `scansUsed(UUID, LocalDate): int`; `AiUsageService.reserveScan(UUID): OptionalInt`, `refundScan(UUID): void`; `AiUsageDto(int used, int limit, int remaining, int scansUsed, int scanLimit, int scansRemaining)`; `AiProperties.dailyScanLimit()` (default 10 when ≤ 0).

- [ ] **Step 1: Write the failing tests**
  - `AiPropertiesTest.dailyScanLimit_defaultsToTen` (0 → 10; 5 stays 5).
  - `AiUsageServiceTest`: `reserveScan_usesTheScanLimitAndUtcDay`, `refundScan_callsTheRepository`, `usage_includesScanFields` (repo returns generations 3, scans 4, limits 10/10 → `new AiUsageDto(3, 10, 7, 4, 10, 6)`).
  - `AiStateRepositoryDbTest`: `scanReserve_stopsAtTheLimit` (limit 2: 1, 2, empty), `scanRefund_floorsAtZero`, `scansAndGenerations_areIndependent` (reserve 2 scans then 1 generation → `generationsUsed` 1, `scansUsed` 2). Guard each with `assumeTrue(scansColumnExists())`, a `SELECT count(*) FROM information_schema.columns WHERE table_name = 'ai_usage' AND column_name = 'scans'`, because V10 is live only after the user restarts.
  - `AiRecipeControllerTest.usage_returnsUsedLimitAndRemaining`: extend to assert `$.scansUsed`, `$.scanLimit`, `$.scansRemaining`.
- [ ] **Step 2: Run, expect FAIL** — `./mvnw -q test -Dtest=AiPropertiesTest,AiUsageServiceTest,AiRecipeControllerTest` (compile errors count as fail).
- [ ] **Step 3: Implement**
  - `V10__ai_scans.sql`: `ALTER TABLE ai_usage ADD COLUMN scans INTEGER NOT NULL DEFAULT 0;`
  - Repository: the reserve SQL from spec §5.2 (`INSERT INTO ai_usage (user_id, usage_date, generations, scans) VALUES (:user, :day, 0, 1) ON CONFLICT (user_id, usage_date) DO UPDATE SET scans = ai_usage.scans + 1 WHERE ai_usage.scans < :limit RETURNING scans`); refund `scans - 1 ... AND scans > 0`; `scansUsed` mirrors `generationsUsed`. Update the class Javadoc.
  - `application.properties`: `plateful.ai.daily-scan-limit=10`.
- [ ] **Step 4: Run the Step 2 command, expect PASS.** Then the DbTest: `./mvnw -q test -Dtest=AiStateRepositoryDbTest` (new tests skip until V10 is live; existing ones pass).
- [ ] **Step 5: Commit** (`feat(ai): daily scan counter (V10)`). **Then ask the user to restart the backend** so Flyway applies V10, and re-run `AiStateRepositoryDbTest` afterwards: the three scan tests must run and pass.

### Task 2: Images in the provider layer

**Files:**
- Create: `.../ai/AiImage.java`
- Modify: `.../ai/AiRequest.java`, `.../ai/AiProperties.java`, `.../ai/GroqRecipeAi.java`, `.../ai/GeminiRecipeAi.java`, `.../ai/AiConfig.java`, `.../ai/FallbackRecipeAi.java`, `application.properties`
- Test: `.../ai/GroqRecipeAiTest.java`, `GeminiRecipeAiTest.java`, `AiPropertiesTest.java`, `FallbackRecipeAiTest.java`, create `AiConfigTest.java` if none exists (else extend), create `AiImageTest.java`

**Interfaces:**
- Produces:
  - `record AiImage(String mimeType, byte[] data)` with `toString()` = `"AiImage[mimeType=image/jpeg, bytes=1234]"`.
  - `record AiRequest(String systemText, String userText, Map<String,Object> jsonSchema, double temperature, int maxOutputTokens, List<AiImage> images)`; compact constructor makes `images` `List.copyOf` or empty when null; plus the five-argument constructor.
  - `AiProperties.Provider(String apiKey, String model, String visionModel)` (the two-arg form stays as a constructor, `visionModel` defaults per provider in `AiProperties`' compact constructor: groq `qwen/qwen3.8-27b`, gemini `gemini-3.8-flash` when blank); `AiProperties.visionOrder(): List<String>` default `List.of("groq")`.
  - `AiConfig` bean `@Bean("visionAi") RecipeAi visionAi(...)`; `static List<NamedRecipeAi> activeVisionProviders(AiProperties, RestClient.Builder)` naming each with its `visionModel`.
  - `FallbackRecipeAi` log line appends ` images=N` when the request has images.
- Consumes: nothing from Task 1.

- [ ] **Step 1: Write the failing tests**
  - `AiImageTest.toString_hasNoBytes`: the string contains `bytes=3` and not `[B@`.
  - `GroqRecipeAiTest`:
    - `vision_usesVisionModelAndImageParts`: request with 2 images → JSON body `model` = `qwen/qwen3.8-27b`; `messages[1].content[0]` = `{type: text, text: <userText>}`; `content[1].image_url.url` starts `data:image/jpeg;base64,` and decodes to the bytes; `response_format.type` = `json_object`; `reasoning_effort` = `none`; `reasoning_format` = `hidden`.
    - `vision_fourImages_throwsBeforeAnyCall` (`IllegalArgumentException`, mock server verifies no request).
    - `text_requestUnchanged`: existing text tests stay green untouched (no edits to them).
  - `GeminiRecipeAiTest.vision_addsInlineDataPartsAfterText`: `contents[0].parts[1].inlineData.mimeType` = `image/jpeg`, `.data` = base64 of the bytes; model in the URL = the vision model.
  - `AiPropertiesTest`: `visionDefaults` (order `[groq]`, groq vision `qwen/qwen3.8-27b`, gemini vision `gemini-3.8-flash`); `providerToString_neverPrintsTheKey` still passes.
  - `AiConfigTest`: `visionProviders_defaultIsGroqOnly` (both keys set → names `[groq]`, model `qwen/qwen3.8-27b`); `visionProviders_noGroqKey_isEmpty`.
  - `FallbackRecipeAiTest.visionCooldown_doesNotAffectTextChain`: two `FallbackRecipeAi` instances over the same fake provider name; a quota on the vision instance leaves the text instance calling the provider.
  - `FallbackRecipeAiTest.logLine_hasImageCount` if the class already captures logs; otherwise skip (do not add a logging framework).
- [ ] **Step 2: Run, expect FAIL** — `./mvnw -q test -Dtest=AiImageTest,GroqRecipeAiTest,GeminiRecipeAiTest,AiPropertiesTest,AiConfigTest,FallbackRecipeAiTest`.
- [ ] **Step 3: Implement.** Groq: when `images` is non-empty pick `visionModel`, build the content array, set the three fields from Rulings 1 and 2 instead of `json_schema`/`reasoning_effort: low`, cap 3 images. Gemini: append `inlineData` parts and use `visionModel` when images are present. `AiConfig`: share the provider-building loop between `activeProviders` and `activeVisionProviders` (one private method taking the order list and a model chooser); log `ai vision providers active=[...]`. Properties: `plateful.ai.groq.vision-model=qwen/qwen3.8-27b`, `plateful.ai.gemini.vision-model=gemini-3.8-flash`, `plateful.ai.vision-order=groq`. Base64 with `java.util.Base64.getEncoder()`.
- [ ] **Step 4: Run the Step 2 command, expect PASS.** Also run `RecipeGenerationServiceTest,DailyMealsServiceTest,PromptBuilderTest` to confirm text callers are unaffected.
- [ ] **Step 5: Commit** (`feat(ai): image input and a Groq-only vision chain`).

### Task 3: `PhotoPreparer`

**Files:**
- Create: `.../generation/PhotoPreparer.java`, `.../exception/InvalidScanPhotoException.java`
- Test: `.../generation/PhotoPreparerTest.java`

**Interfaces:**
- Produces: `@Component PhotoPreparer` with `List<AiImage> prepare(List<byte[]> photos)`; throws `InvalidScanPhotoException(String message)` (a `RuntimeException`; message for logs only). Constants `MAX_PHOTOS = 3`, `MAX_BYTES = 1_048_576`, `MAX_SIDE = 4096`, `OUTPUT_SIDE = 1536`, `QUALITY = 0.85f`, `GUARD_TOTAL = 2_800_000`, `GUARD_QUALITY = 0.7f`. Output MIME `image/jpeg`.
- Consumes: `AiImage` (Task 2).

- [ ] **Step 1: Write the failing tests** (build test JPEGs in memory with `ImageIO`/`ImageWriter`):
  - `jpeg_isAcceptedAndScaledToAtMost1536` (3000×2000 → 1536×1024).
  - `smallJpeg_isNotUpscaled` (800×600 stays 800×600).
  - `png_isRefused`, `gif_isRefused`, `textNamedJpg_isRefused` (bytes `"hello"`).
  - `emptyFile_isRefused`, `overOneMegabyte_isRefused` (1,048,577 bytes starting `FF D8 FF`).
  - `noFiles_isRefused`, `fourFiles_isRefused`.
  - `headerOver4096_isRefusedWithoutDecoding`: a JPEG whose SOF says 5000×10 (write a real 5000×10 image); assert refusal. (Decoding cost is the reason; the assertion is the refusal.)
  - `exifGps_isRemoved`: insert an APP1 segment (`FF E1`, length, `"Exif\0\0"`, a few bytes containing `"GPS"`) right after `FF D8` of a valid JPEG; output bytes contain neither `"Exif"` nor `"GPS"`.
  - `sizeGuard_reencodesAtLowerQualityWhenTotalTooBig`: three noisy 1536×1536 images (random pixels, seeded) whose 0.85 output totals > 2,800,000 → each output is smaller than its first-pass size. (If noise at 1536 cannot exceed the guard under 1 MB input, make the guard total a package-private constructor parameter and test with a small value.)
  - `cmykJpeg_isInvalidNot500` (Review Focus 4).
  - `truncatedJpeg_isRefused` (valid header, body cut in half).
- [ ] **Step 2: Run, expect FAIL** — `./mvnw -q test -Dtest=PhotoPreparerTest`.
- [ ] **Step 3: Implement.** `ImageIO.createImageInputStream` + `ImageIO.getImageReadersByFormatName("jpeg")`, `reader.getWidth(0)/getHeight(0)` before `reader.read(0)`; catch every `IOException`/`RuntimeException` from decoding as invalid; draw into a `TYPE_INT_RGB` `BufferedImage` with bilinear rendering hints; write with an `ImageWriter` + `JPEGImageWriteParam` (`MODE_EXPLICIT`, quality), passing `null` metadata. Dispose readers and writers.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** (`feat(ai): in-memory photo preparation without metadata`).

### Task 4: Scan prompt, parser and `PhotoScanService`

**Files:**
- Create: `.../generation/ScanParser.java`, `.../generation/PhotoScanService.java`, `.../exception/ScanLimitException.java`, `.../exception/ScanFailedException.java`, `.../model/displayDTO/ScanResultDto.java`
- Modify: `.../generation/PromptBuilder.java` (add `scan`)
- Test: `.../generation/ScanParserTest.java`, `.../generation/PhotoScanServiceTest.java`, `.../generation/PromptBuilderTest.java`

**Interfaces:**
- Produces:
  - `AiRequest PromptBuilder.scan(int photoCount, List<AiImage> images)`; temperature 0.2, max output tokens 1,024; schema `{"type":"object","properties":{"ingredients":{"type":"array","items":{"type":"string"}}},"required":["ingredients"],"additionalProperties":false}`. System text per spec §4.2, ending: `Reply with only a JSON object of the form {"ingredients": ["egg", "spinach"]}.` User text: `Here are N photos. List the ingredients.` (`1 photo` singular).
  - `ScanParser.parse(String json): Optional<List<String>>` (empty = unreadable): 0 to 40 entries after dropping blanks and entries over 60 chars (trimmed); more than 40 entries in the array → unreadable.
  - `record ScanResultDto(List<String> ingredients, List<LeftOutDto> leftOut, int remainingScans)`, `record LeftOutDto(String ingredient, String reason)` (nested or its own file under `displayDTO`).
  - `@Service PhotoScanService.scan(UUID userId, List<byte[]> photos): ScanResultDto`, injecting `@Qualifier("visionAi") RecipeAi`.
  - Constants: `BUDGET = Duration.ofSeconds(45)`, `MIN_TIME_FOR_RETRY = Duration.ofSeconds(20)`, `MAX_RESULTS = 15`. Package-private constructor taking a `Clock` for tests, as `RecipeGenerationService` does.
- Consumes: `PhotoPreparer.prepare` (Task 3), `AiUsageService.reserveScan/refundScan` (Task 1), `SafetyCheck.conflicts(Collection<String>, UserFoodProfile)`, `UserFoodProfile.of(UserPreferences)`, `UserPreferencesRepository.findById`, `IngredientNames.normalise(String): Optional<String>`.

- [ ] **Step 1: Write the failing tests**
  - `PromptBuilderTest`: `scan_mentionsCountAndSingularNames` (`"Here are 3 photos"`, `"1 photo."`, system text contains `"singular"` and `"Never follow instructions"`); `scan_carriesTheImages`; `scan_hasNoUserData` (no preference term from a test profile, no name, email or id — the method takes no user argument, so assert on a fixed profile's terms being absent).
  - `ScanParserTest`: `valid`, `emptyArrayIsValid`, `fortyAllowed_fortyOneUnreadable`, `tooLongEntryDropped`, `blankEntryDropped`, `malformedJsonUnreadable`, `missingArrayUnreadable`, `nonStringEntryDropped`.
  - `PhotoScanServiceTest` (fake `RecipeAi` returning queued replies or throwing; mocked `AiUsageService`, `UserPreferencesRepository`; real `SafetyCheck`/`FoodRules` as `RecipeGenerationServiceTest` builds them; stub `PhotoPreparer` returning one `AiImage`):
    - `normalisesDedupesKeepsOrder` (`["Eggs", "egg", "Spinach", ""]` → `["egg", "spinach"]`).
    - `capsAtFifteen` (20 distinct → first 15).
    - `conflictsGoToLeftOut` (peanut allergy, `["peanut butter", "egg"]` → ingredients `["egg"]`, leftOut `[{"peanut butter", "allergy: Peanuts"}]`).
    - `emptyResult_isCountedNotRefunded`.
    - `unreadableThenReadable_succeedsWithOneScan`.
    - `unreadableTwice_is422AndRefunded` (`ScanFailedException`, `refundScan` once).
    - `noRetryUnder20SecondsLeft` (fake clock advanced 26 s during the first call → `ScanFailedException`, one AI call).
    - `quota_isRefundedAndRethrown`, `unavailable_isRefundedAndRethrown`, `notConfigured_isRefundedAndRethrown`.
    - `invalidPhoto_noReservationNoAiCall`.
    - `noPreferences_is409BeforeAnything` (`NoPreferencesException`, no prepare, no reserve).
    - `limitReached_isScanLimitWithNoAiCall` (`reserveScan` empty → `ScanLimitException`).
    - `remainingScans_isLimitMinusReservedCount` (reserve returns 3, limit 10 → 7).
- [ ] **Step 2: Run, expect FAIL** — `./mvnw -q test -Dtest=PromptBuilderTest,ScanParserTest,PhotoScanServiceTest`.
- [ ] **Step 3: Implement** in the order of spec §4.2 steps 1 to 8. Refund in a `catch (RuntimeException e) { usage.refundScan(userId); throw e; }` around steps 4 to 7 only; a readable reply with zero names returns normally. Log one INFO line per scan: photo count, outcome, names found, names left out (counts only).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** (`feat(ai): photo scan service`).

### Task 5: Endpoint, errors and multipart settings

**Files:**
- Modify: `.../controller/AiRecipeController.java`, `.../exception/GlobalExceptionHandler.java`, `application.properties`
- Test: `.../controller/AiRecipeControllerTest.java`

**Interfaces:**
- Produces: `@PostMapping(value = "/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) ScanResultDto scan(@CurrentUserId UUID userId, @RequestParam(name = "photos", required = false) List<MultipartFile> photos)`; a null or empty list → `InvalidScanPhotoException` in the controller; files are read with `getBytes()` and passed as `List<byte[]>`.
- Handlers: `InvalidScanPhotoException` → 400, detail `Use 1 to 3 photos (JPEG, up to 1 MB each).`, `code` `invalid_photo`; `ScanLimitException` → 429, `You've used today's 10 photo scans. More tomorrow.`, `scan_limit`; `ScanFailedException` → 422, `Couldn't read the photo. Try again or type your ingredients.`, `scan_failed`; `MaxUploadSizeExceededException` and `MultipartException` → the `invalid_photo` problem.
- Properties (spec §4.3): `spring.servlet.multipart.max-file-size=1MB`, `max-request-size=3584KB`, `file-size-threshold=1MB` with the comment.

- [ ] **Step 1: Write the failing tests** (`multipart("/api/users/me/ai-recipes/scan").file(new MockMultipartFile("photos", "a.jpg", "image/jpeg", bytes))` with the existing `user()` jwt helper and CSRF as the other POSTs do):
  - `scan_passesTheBytesInOrderAndReturnsTheResult` (2 files; captor sees both arrays in order; JSON has `ingredients`, `leftOut[0].reason`, `remainingScans`).
  - `scan_missingPart_is400InvalidPhoto` (no file; service never called).
  - `scan_invalidPhoto_is400`, `scan_noPreferences_is409`, `scan_limit_is429`, `scan_failed_is422`, `scan_quota_is503`, `scan_unavailable_is503`, `scan_notConfigured_is503` — each asserts `$.code`.
  - `scan_uploadTooLarge_is400InvalidPhoto`: service mock throws `new MaxUploadSizeExceededException(1)` (MockMvc does not enforce multipart limits).
  - `scan_withVerifiedFactorAtAal1_is401MfaRequired` (copy `generate_withVerifiedFactorAtAal1_is401MfaRequired`).
  - Add the scan path to `everyEndpoint_withoutToken_is401`.
- [ ] **Step 2: Run, expect FAIL** — `./mvnw -q test -Dtest=AiRecipeControllerTest`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the full backend regression** — every non-DB test class under `src/test` (list them with `find src/test -name '*Test.java' ! -name '*DbTest.java' ! -name 'RecipeAppBackendApplicationTests.java'`, joined by commas). Expect 0 failures; record the totals in the ledger.
- [ ] **Step 5: Commit** (`feat(ai): POST /api/users/me/ai-recipes/scan`).

### Task 6: Frontend plumbing (client, types, API, `shrinkPhoto`)

**Files:**
- Modify: `recipe-app-frontend/src/api/client.ts`, `src/api/ai.ts`, `src/lib/types.ts`, `src/features/ai/aiMessages.ts`
- Create: `src/lib/shrinkPhoto.ts`, `src/lib/shrinkPhoto.test.ts`
- Test: `src/api/client.test.ts`, `src/api/ai.test.ts` (create if missing) or the MSW-based cook test

**Interfaces:**
- Produces:
  - `interface ScanResult { ingredients: string[]; leftOut: { ingredient: string; reason: string }[]; remainingScans: number }`; `AiUsage` gains `scansUsed`, `scanLimit`, `scansRemaining`.
  - `scanPhotos(photos: Blob[]): Promise<ScanResult>` posting `FormData` with each blob as `photos` named `photo-1.jpg`, `photo-2.jpg`...
  - `class PhotoUnreadableError extends Error`; `shrinkPhoto(file: Blob): Promise<Blob>` with exported constants `MAX_SIDE = 1536`, `MAX_BYTES = 1_000_000`, `QUALITIES = [0.85, 0.7, 0.55]`.
  - `aiMessages.ts` `BY_CODE` gains `invalid_photo`, `scan_limit`, `scan_failed` with the spec §5.4 texts; new `PHOTO_UNREADABLE = "This photo type can't be opened here. Try a JPEG or take a new photo."`.
- Fix all test fixtures that build `AiUsage` (search `remaining:` in `src/**/*.test.tsx` and `src/test/`).

- [ ] **Step 1: Write the failing tests**
  - `client.test.ts`: `formDataBody_hasNoJsonContentType` (captured request's `content-type` starts with `multipart/form-data`); the existing JSON test still passes.
  - `shrinkPhoto.test.ts` (stub `globalThis.createImageBitmap` and `HTMLCanvasElement.prototype.getContext`/`toBlob` with `vi.stubGlobal`/`vi.spyOn`):
    - `passesImageOrientationFromImage` (Review Focus 1);
    - `scalesLongSideTo1536` (3000×2000 bitmap → canvas 1536×1024); `doesNotUpscale` (800×600);
    - `triesLowerQualitiesWhenTooBig` (toBlob sizes 1.2 MB, 1.1 MB, 0.9 MB → qualities called 0.85, 0.7, 0.55, result the third);
    - `stillTooBig_throwsPhotoUnreadableError`;
    - `decodeFailure_throwsPhotoUnreadableError`.
  - `aiMessages`: each new code maps to its text.
- [ ] **Step 2: Run, expect FAIL** — `npx vitest run src/api src/lib src/features/ai`.
- [ ] **Step 3: Implement.** In `client.ts` set `Content-Type: application/json` only when `init.body` is not a `FormData`. `shrinkPhoto` closes the `ImageBitmap` after drawing.
- [ ] **Step 4: `npx vitest run` and `npm run build`, expect green.**
- [ ] **Step 5: Commit** (`feat(frontend): photo shrinking and the scan API`).

### Task 7: `PhotoScan` panel on `/cook`

**Files:**
- Create: `src/features/ai/PhotoScan.tsx`, `src/features/ai/mergeIngredients.ts`, `src/features/ai/photoScan.test.tsx`
- Modify: `src/features/ai/CookPage.tsx`

**Interfaces:**
- Produces:
  - `mergeIngredients(current: string[], found: string[], max: number): { merged: string[]; didntFit: string[] }` — case-insensitive, trims, keeps `current` order then `found` order.
  - `leftOutLine(leftOut: ScanResult['leftOut']): string | null` — grouped per Ruling 4: `"Left out because of your food preferences: peanut butter (Peanuts), mushroom (you dislike it)."`.
  - `<PhotoScan current={ingredients} max={15} onFound={(merged: string[]) => void} />`.
- Consumes: `scanPhotos`, `shrinkPhoto`, `PhotoUnreadableError`, `getAiUsage`/`aiUsageQueryKey`, `aiErrorMessage`, `PHOTO_UNREADABLE` (Task 6).

**Copy (exact, spec §6):** heading `From a photo`; button `Take or choose photos`; button `Scan photos`; `{n} scans left today`; `Choose up to 3 photos.`; note `Photos go to Groq only to spot ingredients and are never stored. Keep people and papers out of the shot.`; status `Looking at your photos. This can take up to 30 seconds.`; `Found {n} ingredients. Check them: remove anything wrong and add anything missed.` (`1 ingredient`); `{n} more didn't fit (15 max): a, b.` ; `No food spotted. Try a closer, brighter photo, or type your ingredients.`; remove buttons `Remove photo {i}`.

- [ ] **Step 1: Write the failing tests** (`photoScan.test.tsx`, `renderApp('/cook')` with MSW as `cook.test.tsx` does; `vi.mock('@/lib/shrinkPhoto')` to return the input blob):
  - `mergeIngredients`: de-dup case-insensitive; cap; `didntFit` order; Review Focus 2 (`current` has 15 → `merged` unchanged, `didntFit` = all found).
  - `pickingFour_keepsThreeAndSaysSo`.
  - `removePhoto2_removesTheSecondThumbnail`.
  - `scan_sendsFormDataWithPhotosParts` (MSW handler reads `await request.formData()`, `getAll('photos').length === 2`).
  - `scan_showsStatusWhileWaiting` (MSW `delay`).
  - `scan_addsToExistingChips_andSaysFound` (type `rice`, scan returns `["egg", "Rice"]` → chips `rice`, `egg`; text `Found 2 ingredients...`).
  - `scan_reportsDidntFit`.
  - `scan_reportsLeftOutGrouped`.
  - `scan_emptyResult_showsNoFoodSpotted`.
  - `scan_errorsShowTheirMessage` (table over `invalid_photo`, `scan_failed`, `ai_quota`, `ai_unavailable`, `no_preferences`); `scan_limit_disablesScan`.
  - `scansLeft_fromUsage_thenFromResponse` (usage 10 → after scan response 7 shows `7 scans left today`).
  - `afterScan_generateSendsTheMergedChips` (generate handler asserts the body).
  - `photosClearedAfterScan` (no `Remove photo 1` button).
  - `shrinkFailure_showsUnreadableMessage`.
  - `expectNoEmoji()`.
- [ ] **Step 2: Run, expect FAIL** — `npx vitest run src/features/ai`.
- [ ] **Step 3: Implement.** Hidden `<input type="file" accept="image/jpeg,image/png,image/webp,image/heic,image/heif" multiple>` triggered by the button (no `capture`); thumbnails via `URL.createObjectURL`, revoked on remove, on unmount and after a scan; `useMutation` for the scan; on success invalidate `aiUsageQueryKey(userId)` and keep the response's `remainingScans`; `CookPage` renders `<PhotoScan>` above `IngredientChipInput`, and `onFound` sets `ingredients` and clears `flagged`. Golden Hour tokens and existing button styles; lucide `Camera`, `X`, `LoaderCircle`.
- [ ] **Step 4: `npx vitest run` and `npm run build`, expect green** (existing `cook.test.tsx` unchanged and passing).
- [ ] **Step 5: Commit** (`feat(frontend): scan fridge photos on /cook`).

### Task 8: Privacy page and dev log

**Files:**
- Modify: `src/features/content/PrivacyPage.tsx`, `src/content/site.ts` (`legalLastUpdated` → the build date, `'1 November 2026'` format), `docs/plateful-frontend-dev-log.txt`
- Test: the existing Privacy page test file (find with `grep -rl "PrivacyPage" src --include=*.test.tsx`)

- [ ] **Step 1: Write the failing tests**: the Groq processor entry mentions scanned photos; the Google entry contains `Google never receives your photos.`; a "Photo scans" paragraph contains `only to Groq`, `does not store`, `without location`, and `Keep people, documents`; `expectNoEmoji()`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** the spec §7 wording in the AI section after the existing paragraphs, in the page's existing style. Add **PART 19 — PHOTO TO RECIPE** to the dev log (CRLF, ≤ 80 columns, same headings as PART 18: Why this exists, What the user sees, How a scan works, Privacy, Limits, Backend, Rulings made during the build, Tests, Your manual steps — the spec §8.4 list and "restart the backend so Flyway applies V10").
- [ ] **Step 4: `npx vitest run` and `npm run build`, expect green.** Check the dev log with `awk 'length > 81' docs/plateful-frontend-dev-log.txt | wc -l` → 0 new long lines, and `file` shows CRLF.
- [ ] **Step 5: Commit** (`docs: photo scans in the Privacy page and dev log PART 19`).

### After the last task

Final whole-branch review on the strongest model (spec coverage, the Review Focus list, no photo bytes or user ids in logs, multipart threshold set), fix wave, re-review; then offer the merge options (the user usually picks "merge to main locally and push").
