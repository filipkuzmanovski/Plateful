# Reports and Instant Publishing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recipes go live when saved; signed-in people report recipes and comments; 3 reports hide an item; admins remove or dismiss from a Reports queue.

**Architecture:** Keep `recipes.status` and add `hidden_at` to recipes and comments. "Live" means `status = 'approved' AND hidden_at IS NULL`, and every public read switches from "approved" to "live". A new `content_reports` table holds reports. A `ReportService` writes them under the item's row lock and hides at 3, and an admin queue lists, dismisses or (through the existing removal) deletes. The approve/reject review flow is removed.

**Tech Stack:** Spring Boot 4, Java 21, Flyway, Postgres (Supabase), JPA; React 19, TypeScript, Tailwind v4, TanStack Query, Vitest, MSW.

**Spec:** `docs/superpowers/specs/2026-11-07-reports-and-instant-publishing-design.md`

## Global Constraints

- JAVA_HOME = `~/.jdks/ms-21.0.9`. Never run unfiltered `mvnw test`; run `./mvnw test "-Dtest=!RecipeAppBackendApplicationTests,!*DbTest"` or named classes.
- DbTests: opt-in on `SUPABASE_DB_PASSWORD`, `@DataJpaTest`, Flyway disabled, never `@Import`; tests that need V15 skip with `assumeTrue` until it is live.
- Migration is `V15__reports_and_instant_publishing.sql`, exactly the SQL in spec section 2.
- Report reasons are exactly `spam`, `offensive`, `unsafe`, `not_food`, `other`. Labels: Spam; Offensive or hateful; Unsafe food advice; Not food related; Other.
- Hide threshold is 3 distinct reporters with `open` reports. Note max 500 characters. Limit is 20 reports per reporter per rolling 24 hours.
- Error codes: `invalid_report` (400), `own_content` (403), `already_reported` (409), `report_limit` (429), `already_handled` (409). A missing or hidden item is the existing 404 from `ResourceNotFoundException`.
- Copy is fixed by spec section 4: "Thanks. An admin will take a look." / "You've already reported this." / "You've sent 20 reports today. You can report more tomorrow." / "This is no longer available." / "Hidden while an admin looks at reports." / "Not published. Edit it to publish it." / "Your recipe goes live as soon as you save it." / "This was already handled." / "Reported by N people".
- No emojis in the UI. Colours come from the role-named tokens in `src/index.css`.
- Commits stage explicit paths only and never `tools/dataset/README.md`.

## Review Focus

1. A hidden recipe leaking into a list: browse, search, cuisine counts, an author page, cookbooks, the novelty comparison or calorie estimates. Pinned by `hiddenRecipe_*` tests in Task 1 and `LiveRecipeQueriesDbTest` in Task 3.
2. Two people sending the third report at once: hide once, no duplicate rows. Pinned by `thirdReport_hidesOnce` and the unique constraints in Task 3.
3. A dismiss racing a new report: the new report stays `open`. Pinned by `dismiss_onlyClosesReportsThatExistedBeforeIt` in Task 4.
4. An author editing a hidden recipe: it stays hidden. Pinned by `update_keepsHiddenAt` in Task 2.
5. A comment on a hidden recipe: it can't be reported or reacted to, and the recipe's 404 wins. Pinned by `reportComment_onHiddenRecipe_is404` in Task 3.

---

### Task 1: V15, the hidden mark and the "live" rule (backend)

**Files:**
- Create: `RecipeAppBackend/src/main/resources/db/migration/V15__reports_and_instant_publishing.sql`
- Modify: `model/entities/Recipe.java`, `model/entities/RecipeComment.java` (add `OffsetDateTime hiddenAt`, column `hidden_at`)
- Modify: `service/RecipeVisibility.java`, `specification/RecipeSpecifications.java`, `repository/RecipeRepository.java`, `service/RecipeService.java` (`getByAuthor`), `service/CookbookService.java` (`requireLikedAndApproved`), `repository/PlatefulComparisonRepository.java`, `nutrition/NutritionEstimates.java`, `repository/NutritionEstimateRepository.java`
- Test: `RecipeVisibilityTest`, `RecipeServiceBrowseTest`, `CookbookServiceTest`, `NutritionEstimatesTest` (extend)

All Java paths are under `RecipeAppBackend/src/main/java/mk/ukim/finki/wp/recipeappbackend/` and tests under the matching `src/test` path.

**Interfaces:**
- Produces: `Recipe.isLive()` returning `status == APPROVED && hiddenAt == null`; `Recipe.getHiddenAt()/setHiddenAt`; `RecipeComment.getHiddenAt()/setHiddenAt`; `RecipeSpecifications.liveOnly()` (replaces `approvedOnly()`); `RecipeRepository.findByUserIdAndStatusAndHiddenAtIsNull(UUID, RecipeStatus, Pageable)`.

- [ ] **Step 1: Write failing tests.**
  - `RecipeVisibilityTest.hiddenRecipe_isNotVisibleToOthers`: an approved recipe with `hiddenAt` set → `canSee(recipe, stranger)` is false, and `canSee` for the author and for an aal2 admin is true.
  - `RecipeVisibilityTest.hiddenRecipe_refusesInteractionForEveryone`: `requireApprovedForInteraction` throws `RecipeNotApprovedException` for the author and the admin.
  - `RecipeVisibilityTest.hiddenRecipe_strangerGetsUnavailable`: `requireVisible` for a stranger throws `RecipeHiddenException`, and the handler's code is `unavailable`.
  - `RecipeServiceBrowseTest.hiddenRecipe_isLeftOutOfBrowseAndAuthorPage`: the spec passed is `liveOnly()`, and `getByAuthor` for a stranger calls `findByUserIdAndStatusAndHiddenAtIsNull`.
  - `CookbookServiceTest.hiddenRecipe_cannotGoInABook`: `CookbookConflictException`.
  - `NutritionEstimatesTest.hiddenRecipe_hasNoEstimateTarget`.
- [ ] **Step 2: Run them and see them fail.** `./mvnw test "-Dtest=RecipeVisibilityTest,RecipeServiceBrowseTest,CookbookServiceTest,NutritionEstimatesTest"`.
- [ ] **Step 3: Implement.**
  - Write V15 as spec section 2.
  - `canSee`: `recipe.isLive() || isAuthorOrAdmin`.
  - `requireApprovedForInteraction`: refuse unless `isLive()`.
  - `RecipeHiddenException` code: `unavailable` unless status is `PENDING`.
  - Native SQL gets `AND hidden_at IS NULL` (cuisine counts, novelty comparison, estimate targets). JPQL and Java checks use `isLive()`.
- [ ] **Step 4: Run the tests and the full non-DB suite; all pass.**
- [ ] **Step 5: Commit** the files above with `feat: hidden mark and live rule for recipes`.

### Task 2: Instant publishing and removal of the review flow

**Files:**
- Modify: `service/RecipeService.java` (`create`, `update`, delete `enterPending`), `model/displayDTO/RecipeReviewDto.java` (add `boolean hidden`), `service/SavedRecipeService.java` (`stubOf` always `unavailable`)
- Delete: `controller/AdminReviewController.java`, `service/AdminReviewService.java`, `service/RecipeReviewedEvent.java`, `mail/ReviewEmailListener.java`, `exception/ReviewConflictException.java` (if nothing else uses it), and their tests: `AdminReviewControllerTest`, `AdminReviewServiceTest`, `ReviewEmailListenerTest`, `ReviewEmailTransactionTest`, `AdminQueueDbTest`
- Modify tests that assert pending behaviour: `RecipeServiceReviewStatusTest`, `RecipeControllerTest`, `SavedRecipeServiceTest`, `GlobalExceptionHandlerTest`

**Interfaces:**
- Consumes: Task 1's `Recipe.isLive()`.
- Produces: `RecipeReviewDto(String status, String rejectionReason, boolean everApproved, int revision, OffsetDateTime submittedAt, boolean hidden)`. JSON gains `review.hidden`, sent to author and admins only.

- [ ] **Step 1: Rewrite `RecipeServiceReviewStatusTest` with failing tests.**
  - `create_byAnyUser_isApprovedAtOnce`: status `APPROVED`, `everApproved` true, `approvedTitle` equal to the title, `reviewedBy` null.
  - `update_ofRejectedRecipe_publishesIt`.
  - `update_keepsHiddenAt`: a hidden recipe stays hidden after an edit.
  - `update_bumpsRevision`.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
  - `create` and `update` call a `publish(Recipe)` helper that sets status `APPROVED`, `everApproved`, `approvedTitle = title`, `rejectionReason = null`, `reviewedAt = now`, `reviewedBy = null`.
  - Delete the files listed and any code only they use. `InvalidReviewReasonException` stays if removal uses it.
  - Update the remaining tests to the new behaviour.
- [ ] **Step 4: Run the full non-DB suite; all pass.** `AdminReviewSchemaDbTest` and `RecipeVisibilityDbTest` still compile. Fix any pending-flow assertion in them to match instant publishing.
- [ ] **Step 5: Commit** with `feat: recipes go live when saved; remove approve and reject`.

### Task 3: Reporting (backend)

**Files:**
- Create: `model/entities/ContentReport.java`, `model/ReportReason.java` (enum with `text()`/`fromText` like `RecipeStatus`), `repository/ContentReportRepository.java`, `service/ReportService.java`, `controller/ReportController.java`, `model/createDTO/ReportCreateDto.java`, `exception/ReportException.java` (carries HTTP status and code)
- Modify: `repository/RecipeCommentRepository.java` (add `@Lock(PESSIMISTIC_WRITE) Optional<RecipeComment> findForUpdateById(UUID)` and a comment list that leaves out hidden comments unless they are the viewer's or the viewer is an admin), `service/CommentService.java` (lists; `requireApprovedForInteraction` plus "comment not hidden" for edit and reactions), `service/ReactionService.java`, `model/displayDTO/RecipeCommentDisplayDto.java` (add `boolean hidden`), `exception/GlobalExceptionHandler.java`
- Test: `ReportServiceTest`, `ReportControllerTest`, `CommentServiceTest` (or the existing comment tests), `ContentReportRepositoryDbTest`, `LiveRecipeQueriesDbTest`

**Interfaces:**
- Consumes: Task 1's `hiddenAt` fields and `RecipeRepository.findForReviewById` (row lock).
- Produces:
  - `POST /api/reports`, body `{ recipeId?: UUID, commentId?: UUID, reason: string, note?: string }` → 204.
  - `GET /api/reports/mine?recipeId=…&commentIds=…` → `{ recipeIds: UUID[], commentIds: UUID[] }`.
  - `ReportService.report(UUID reporterId, ReportCreateDto dto)`.
  - `ContentReportRepository.countOpenDistinctReportersForRecipe(UUID)` and `...ForComment(UUID)`.
  - `ContentReportRepository.countByReporterIdAndCreatedAtAfter(UUID, OffsetDateTime)`.

- [ ] **Step 1: Write failing `ReportServiceTest` tests.**
  - `report_recipe_savesOpenReport`
  - `report_ownRecipe_isOwnContent403`
  - `report_ownComment_isOwnContent403`
  - `report_twice_isAlreadyReported409` (also when the first one was dismissed)
  - `report_21stIn24Hours_isReportLimit429`
  - `report_bothIdsOrNeither_isInvalidReport400`
  - `report_unknownReason_isInvalidReport400`
  - `report_note501Chars_isInvalidReport400`
  - `report_blankNote_storedAsNull`
  - `report_hiddenRecipe_is404`
  - `report_recipeOnlyVisibleToAuthor_is404ForOthers`
  - `reportComment_onHiddenRecipe_is404`
  - `secondReport_doesNotHide`
  - `thirdReport_hidesOnce` (`hiddenAt` set on the third, unchanged on a fourth)
  - `dismissedReports_doNotCountTowardHiding`
  - `report_takesTheRowLock` (verifies `findForReviewById` or `findForUpdateById`)
- [ ] **Step 2: Write failing `ReportControllerTest` tests** for 204, each error code and JSON `code`, 401 signed out, MFA `mfa_required` at aal1 with a verified factor, and `GET /mine` shape.
- [ ] **Step 3: Write failing comment tests.**
  - `hiddenComment_leftOutForOthers`
  - `hiddenComment_shownToItsAuthorWithHiddenTrue`
  - `hiddenComment_shownToAdmin`
  - `hiddenComment_refusesReactions`
- [ ] **Step 4: Run and see them fail.**
- [ ] **Step 5: Implement.**
  - The service runs in one transaction. It locks the recipe (`findForReviewById`), or the comment (`findForUpdateById`) and then its recipe.
  - It checks visibility, then ownership, then the daily count, then the unique pair (catch `DataIntegrityViolationException` as a backstop, mapped to 409).
  - It inserts the report, then counts distinct open reporters. At 3 or more with `hiddenAt` null, it sets `hiddenAt = now`.
- [ ] **Step 6: Write `ContentReportRepositoryDbTest`.** It skips until `content_reports` exists. It checks the one-of-two check constraint, the two unique pairs, the reason check, and cascades from recipe, comment and reporter.
- [ ] **Step 7: Write `LiveRecipeQueriesDbTest`.** It skips until `recipes.hidden_at` exists. A hidden approved recipe is absent from browse, cuisine counts, the author page for a stranger, the comparison query and the estimate target query.
- [ ] **Step 8: Run the full non-DB suite; all pass.**
- [ ] **Step 9: Commit** with `feat: report recipes and comments; hide at three reports`.

### Task 4: Admin reports queue (backend)

**Files:**
- Create: `controller/AdminReportController.java`, `service/AdminReportService.java`, `model/displayDTO/ReportedItemDto.java`, `model/displayDTO/OpenReportDto.java`
- Modify: `ContentReportRepository` (queue queries, `dismissOpenForRecipe(UUID)` and `dismissOpenForComment(UUID)` returning the update count), `service/AdminModerationService.java` (comment removal uses `findForUpdateById`)
- Test: `AdminReportServiceTest`, `AdminReportControllerTest`, and an extension of `ContentReportRepositoryDbTest`

**Interfaces:**
- Consumes: Task 3's repository and entity.
- Produces:
  - `GET /api/admin/reports?page=` returns `Page<ReportedItemDto>`. `ReportedItemDto(String kind, UUID id, UUID recipeId, String text, String authorName, OffsetDateTime postedAt, int openCount, boolean hidden, List<OpenReportDto> reports)`, and `OpenReportDto(String reason, String note, OffsetDateTime createdAt)`.
  - `GET /api/admin/reports/count` → `{ "count": n }`.
  - `POST /api/admin/reports/recipes/{id}/dismiss` and `/comments/{id}/dismiss` → 204, 404 or 409 `already_handled`.

- [ ] **Step 1: Write failing tests.**
  - `queue_ordersByOpenCountThenOldestReport`
  - `queue_neverIncludesReporterIds` (the serialized JSON has no `reporter`)
  - `queue_commentEntryCarriesItsRecipeId`
  - `count_countsItemsNotReports`
  - `dismiss_clearsHiddenAndClosesReports`
  - `dismiss_twice_isAlreadyHandled409`
  - `dismiss_missingItem_is404`
  - `dismiss_onlyClosesReportsThatExistedBeforeIt` (dismiss and report both lock the row; a report saved after the dismiss stays open)
  - `dismiss_needsAdminAndAal2` (controller)
  - `remove_deletesTheItemsReports` (removing a reported recipe or comment leaves no reports behind)
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement** with the row lock from Task 3. Dismiss runs `UPDATE … SET state='dismissed' WHERE state='open'` and sets `hiddenAt = null`. Zero rows updated means 409.
- [ ] **Step 4: Run the full non-DB suite; all pass.**
- [ ] **Step 5: Commit** with `feat: admin reports queue with dismiss`.

### Task 5: Frontend, instant publishing and the "live" state

**Files:**
- Modify: `recipe-app-frontend/src/lib/types.ts` (`review.hidden: boolean`, comment `hidden: boolean`, `HiddenState` keeps both values), create `src/lib/live.ts` with `isLive(recipe)`
- Modify: `features/review/ReviewStatusBanner.tsx`, `features/review/StatusBadge.tsx`, `features/likes/LikeButton.tsx`, `features/cookbooks/NewCookbookPage.tsx`, `features/recipe/RecipeDetailPage.tsx` (`acceptsFeedback`), `components/RecipeCard.tsx`, `features/authoring/RecipeFormPage.tsx` (toast "Your recipe goes live as soon as you save it.", remove the "sends back for review" notice)
- Delete: `features/admin/ReviewQueuePage.tsx`, `ReviewRecipePage.tsx`, `RejectDialog.tsx`, and the approve/reject/pending functions in `api/admin.ts`; update `admin.test.tsx` and `authorReview.test.tsx`
- Modify: `AppRoutes.tsx` (`/admin/review` and `/admin/review/:id` → `<Navigate to="/admin/reports" replace />`)

**Interfaces:**
- Produces: `isLive(recipe: RecipeDisplay): boolean`, which is `!recipe.review || (recipe.review.status === 'approved' && !recipe.review.hidden)`.

- [ ] **Step 1: Write failing tests.**
  - Banner: `rejected` shows "Not published. Edit it to publish it."; `hidden` shows "Hidden while an admin looks at reports."; no "Waiting for review" anywhere.
  - Like, rating and comment controls are absent on a hidden own recipe.
  - The form toast after create says "Your recipe goes live as soon as you save it."
  - `/admin/review/x` lands on `/admin/reports`.
- [ ] **Step 2: Run them (`npx vitest run <files>`) and see them fail.**
- [ ] **Step 3: Implement.** Replace each `review.status === 'approved'` gate with `isLive`.
- [ ] **Step 4: Run `npx tsc -b --noEmit` and `npx vitest run`; all pass.**
- [ ] **Step 5: Commit** with `feat(frontend): recipes go live at once; hidden and rejected banners`.

### Task 6: Frontend, the report dialog

**Files:**
- Create: `features/reports/ReportDialog.tsx`, `features/reports/ReportButton.tsx`, `api/reports.ts`, `features/reports/reports.test.tsx`
- Modify: `features/recipe/RecipeDetailPage.tsx` (Report next to Share), `features/recipe/CommentsSection.tsx` (Report per comment; the hidden note under your own hidden comment)

**Interfaces:**
- Consumes: Task 3's endpoints.
- Produces: `reportContent(target: { recipeId: string } | { commentId: string }, reason: ReportReason, note: string | null): Promise<void>`; `getMyReports(recipeId: string, commentIds: string[]): Promise<{ recipeIds: string[]; commentIds: string[] }>`; query key `['myReports', userId, recipeId]`.

- [ ] **Step 1: Write failing tests in `reports.test.tsx`.**
  - The dialog lists the five labels in order.
  - The note counter stops at 500.
  - A send posts the exact body and shows "Thanks. An admin will take a look.", and the button becomes a disabled "Reported".
  - A 409 shows "You've already reported this." and switches the button to "Reported".
  - A 429 shows "You've sent 20 reports today. You can report more tomorrow."
  - A 404 shows "This is no longer available."
  - No Report on your own recipe, your own comment or when signed out.
  - "Reported" shows on load when `/mine` lists the item.
  - A hidden own comment shows "Hidden while an admin looks at reports."
  - `expectNoEmoji()`.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.** Follow the project's existing dialog pattern (see `features/admin/ReasonDialog.tsx`) for focus and labelling.
- [ ] **Step 4: Run tsc and the full vitest run; all pass.**
- [ ] **Step 5: Commit** with `feat(frontend): report recipes and comments`.

### Task 7: Frontend, the admin Reports page

**Files:**
- Create: `features/admin/ReportsPage.tsx`, `features/admin/reportsPage.test.tsx`
- Modify: `api/admin.ts` (`getReports(page)`, `getReportCount()`, `dismissRecipeReports(id)`, `dismissCommentReports(id)`), `components/AppShell.tsx` (link "Reports" to `/admin/reports`, badge from `getReportCount`, aria-label `Reports, N open`), `AppRoutes.tsx` (`/admin/reports` inside `AdminRoute`)

**Interfaces:**
- Consumes: Task 4's endpoints, and the existing `removeRecipe`/`removeComment` and `ReasonDialog`.

- [ ] **Step 1: Write failing tests.**
  - Entries render the Recipe or Comment label, text, author, date, "Reported by 3 people", the Hidden badge and each reason label with its note.
  - Open links to `/recipes/{recipeId}`.
  - Dismiss confirms, posts and removes the entry.
  - Remove opens the reason dialog and removes the entry.
  - A 409 or 404 shows "This was already handled." and refetches.
  - The header shows "Reports" with the count.
  - The empty state reads "No open reports."
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run tsc and the full vitest run; all pass.**
- [ ] **Step 5: Commit** with `feat(frontend): admin reports queue`.

### Task 8: Privacy, Terms, browser check and dev log

**Files:**
- Modify: `features/content/PrivacyPage.tsx`, `features/content/TermsPage.tsx`, `content/site.ts` (`legalLastUpdated` to the ship date), `features/content/content.test.tsx`, `docs/plateful-frontend-dev-log.txt` (new PART, max 80 columns, CRLF)

- [ ] **Step 1: Write failing content tests.**
  - Privacy has a "Reports" part naming who reported, the item, the reason, the note and the date; it says admins don't see the reporter's name, that reports are deleted with the item or account, and that dismissed reports are kept only to stop repeat reports.
  - Terms says content goes live at once, can be hidden after reports, and can be removed with a reason.
  - The date matches.
- [ ] **Step 2: Implement; run tsc, the full vitest run, `npm run build` and `npx oxlint`.**
- [ ] **Step 3: Browser check** with a throwaway fake API, deleted afterwards. Cover the recipe page Report flow, a hidden own recipe, a hidden own comment, the Reports queue (Dismiss, Remove) and the redirects, at desktop and 375 px, light and dark.
- [ ] **Step 4: Append the dev log PART.** It covers the decisions, what changed, checks done, what isn't checked yet (V15 not live until restart; the DbTests skip until then) and the user's manual steps (restart, run `ContentReportRepositoryDbTest` and `LiveRecipeQueriesDbTest` with the password set, report a recipe from 3 accounts, dismiss it).
- [ ] **Step 5: Commit** with `docs: privacy and terms for reports; dev log`.
