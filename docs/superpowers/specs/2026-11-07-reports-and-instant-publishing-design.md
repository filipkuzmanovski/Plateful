# Reports and instant publishing: design

Date: 7 November 2026. Sub-project 1 of 3 of the social plan. The others are
2, video posts with a swipeable feed, and 3, follows with a Following feed.
Each gets its own spec, plan and build.

## 1. Goal

Uploaded recipes go live as soon as they are saved, like posts on a social
app. People who see something wrong report it. Reported items reach an admin
queue, and an admin removes them with a reason or dismisses the reports. This
replaces the approve-before-publishing review from the admin review work
(spec `2026-10-18`, dev log PART 9 onward), and it is the moderation that video
posts (sub-project 2) will use from their first day.

What the user decided:

- Recipes and, later, posts go live at once. Admins see an item only when it is
  reported.
- Recipes and comments can be reported. Posts join when sub-project 2 adds them.
- A report is one reason from a fixed list plus an optional note.
- An item reported by 3 different people hides until an admin acts.
- At the switch, recipes waiting for review go live. Rejected recipes stay
  hidden until their author edits them.
- Approach: keep the `status` column and add hiding, rather than removing
  statuses or reusing `pending` for reported items.

## 2. Data (migration V15)

```sql
-- Recipes waiting for review go live at the switch.
UPDATE recipes
   SET status = 'approved', ever_approved = true, approved_title = title,
       reviewed_at = now(), rejection_reason = NULL
 WHERE status = 'pending';

ALTER TABLE recipes         ADD COLUMN hidden_at TIMESTAMPTZ;
ALTER TABLE recipe_comments ADD COLUMN hidden_at TIMESTAMPTZ;

CREATE TABLE content_reports (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipe_id   UUID REFERENCES recipes(id)         ON DELETE CASCADE,
    comment_id  UUID REFERENCES recipe_comments(id) ON DELETE CASCADE,
    reason      TEXT NOT NULL
                CHECK (reason IN ('spam', 'offensive', 'unsafe', 'not_food', 'other')),
    note        VARCHAR(500),
    state       TEXT NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'dismissed')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((recipe_id IS NULL) <> (comment_id IS NULL)),
    UNIQUE (reporter_id, recipe_id),
    UNIQUE (reporter_id, comment_id)
);
CREATE INDEX idx_reports_open_recipe  ON content_reports (recipe_id)  WHERE state = 'open';
CREATE INDEX idx_reports_open_comment ON content_reports (comment_id) WHERE state = 'open';
CREATE INDEX idx_reports_reporter_day ON content_reports (reporter_id, created_at);
ALTER TABLE content_reports ENABLE ROW LEVEL SECURITY;
```

`hidden_at` is null for every item that is not hidden. The `pending` status
stays allowed by the check constraint, but nothing writes it any more. The
`approved` and `rejected` statuses stay because existing rows use them.

## 3. Rules

### 3.1 Publishing

- Creating a recipe saves it as `approved`, with `ever_approved = true` and
  `approved_title = title`, whoever the author is. `reviewed_by` stays null.
- Editing a recipe keeps it, or makes it, `approved`. Editing a `rejected`
  recipe publishes it. An edit never changes `hidden_at`: a hidden recipe stays
  hidden after its author edits it.
- The row lock that edits, approvals and removals share today
  (`findForReviewById`, `PESSIMISTIC_WRITE`) also covers reports and dismissals
  of recipes. Comments have no such lock today, so the comment repository gets
  a `PESSIMISTIC_WRITE` lookup for reports, dismissals and removal of comments.

### 3.2 Visibility

A recipe is live when `status = 'approved' AND hidden_at IS NULL`.

- Every place that now means "approved" for the public means "live": browse
  and search (`RecipeSpecifications.approvedOnly`), cuisine counts
  (`RecipeRepository` line 50), author pages (`findByUserIdAndStatus`), cookbooks
  (`CookbookService` line 165), saved lists, the novelty comparison
  (`PlatefulComparisonRepository`) and calorie estimates (`NutritionEstimates`,
  `NutritionEstimateRepository`). Each gets a test with a hidden recipe.
- `RecipeVisibility.canSee`: live, or the viewer is the author or an admin.
  `requireApprovedForInteraction` refuses comments, ratings, likes and
  reactions on a hidden recipe, for its author and admins too.
- To anyone else, a hidden recipe that was ever approved is the existing
  "hidden" 404 with code `unavailable` and its last approved title. Saved lists
  show the existing stub with state `unavailable`. The `under_review` code and
  stub state are no longer produced.
- A hidden comment is left out of comment lists for everyone except its author
  and admins, and it takes no new reactions.

### 3.3 Reporting

`POST /api/reports` with `{ "recipeId" | "commentId", "reason", "note" }`,
signed in. The MFA filter guards it like other writes.

- `reason` must be one of the five values and `note` at most 500 characters,
  blank meaning none. Otherwise 400, code `invalid_report`.
- The item must exist and be visible to the reporter. A hidden or missing item
  gives 404, code `not_found`.
- Reporting your own recipe or comment is 403, code `own_content`.
- One report per person per item, counting dismissed reports. A second gives
  409, code `already_reported`.
- At most 20 reports per person per rolling 24 hours, counted from
  `content_reports`. The 21st gives 429, code `report_limit`.
- Inside the item's row lock: insert the report, then count the item's `open`
  reports from distinct reporters. At 3 or more with `hidden_at` null, set
  `hidden_at = now()`.
- `GET /api/reports/mine?recipeId=…` and `?commentIds=…` return which of those
  items the caller has reported, so the page can show "Reported".

### 3.4 Admin queue

Behind the existing admin guard (`/api/admin/**`, `is_admin` plus aal2).

- `GET /api/admin/reports?page=` lists items with at least one open report,
  ordered by open report count descending, then by oldest open report. Each
  entry has its kind (`recipe` or `comment`), id, title or comment text, the
  recipe id (for a comment, the recipe it belongs to), author name, posted
  date, open report count, `hidden` flag, and its open reports as reason, note
  and date. Reporter identity is not included.
- `GET /api/admin/reports/count` returns the number of items with open reports,
  for the header badge.
- `POST /api/admin/reports/recipes/{id}/dismiss` and
  `POST /api/admin/reports/comments/{id}/dismiss` set the item's open reports
  to `dismissed` and clear `hidden_at`, inside the row lock. An item with no
  open reports gives 409, code `already_handled`. A missing item gives 404.
- Remove stays as it is (`/api/admin/recipes/{id}/remove`,
  `/api/admin/comments/{id}/remove`): delete with a reason, emailed to the
  author after commit. Its reports go with it through the cascade.

### 3.5 Accounts

Deleting a reporter's account deletes their reports. A hidden item stays
hidden until an admin decides, even if its open reports fall below 3.

## 4. Screens

### 4.1 Reporting

- A Report button next to Share on a recipe page, and a Report item in each
  comment's actions. Neither shows on your own content, to signed-out
  visitors, or on hidden items.
- The dialog has five radio options: Spam; Offensive or hateful; Unsafe food
  advice; Not food related; Other. Below them is an optional note of up to 500
  characters with a counter, then Send report and Cancel.
- On success the dialog shows "Thanks. An admin will take a look." and the
  button becomes a disabled "Reported". Errors show in the dialog:
  - 409: "You've already reported this." The button switches to "Reported".
  - 429: "You've sent 20 reports today. You can report more tomorrow."
  - 404: "This is no longer available."

### 4.2 Admin

- The header's Review queue link becomes Reports, with the open count as a
  badge, at `/admin/reports`. `/admin/review` and `/admin/review/:id` redirect
  there.
- Each queue entry shows a Recipe or Comment label; the title or comment text;
  the author and posted date; "Reported by N people"; a Hidden badge when
  hidden; and each open report's reason and note. Open goes to the recipe (for
  a comment, the recipe page with the comment). Remove uses the existing
  reason dialog. Dismiss asks for confirmation, then clears the reports.
- After Remove or Dismiss the entry leaves the list. A 404 or 409 shows "This
  was already handled." and refreshes the queue.

### 4.3 Authors

- The "Waiting for review" banner and badges go.
- A rejected recipe shows "Not published. Edit it to publish it."
- A hidden recipe shows "Hidden while an admin looks at reports." to its author.
- A hidden comment shows the same sentence under it, to its author only.
- The recipe form's review wording becomes "Your recipe goes live as soon as
  you save it."

### 4.4 Emails

The removal email stays. The approval and rejection emails go
(`ReviewEmailListener`, `RecipeReviewedEvent`), since nothing is approved or
rejected any more.

## 5. Removed code

The approve and reject endpoints, the pending queue endpoints, their service
code (`AdminReviewService` approve, reject and pending queue), the review
email listener and event, and the frontend `ReviewQueuePage` and
`ReviewRecipePage` with their tests. `AdminReviewController` goes as a whole,
including `GET /api/admin/recipes/{id}`: the queue's Open link uses the normal
recipe page, where `RecipeVisibility` already lets an admin on aal2 see a
hidden recipe.

## 6. Privacy and Terms

- Privacy gains a "Reports" part. It names what is stored: who reported, the
  item, the reason, the note and the date. Admins see the reason and note but
  not the reporter's name. Reports are deleted with the item or the account,
  and dismissed reports are kept only to stop the same person reporting the
  same item again.
- Terms says recipes and comments go live straight away, can be hidden after
  reports, and can be removed by an admin, who gives a reason.
- `SITE.legalLastUpdated` moves to the date this ships.

## 7. Testing

- Backend unit and controller tests cover every rule in section 3:
  - instant publishing on create and edit, including a rejected recipe;
  - each report error code;
  - hiding at the third distinct reporter and not at the second;
  - dismissed reports counting toward "already reported" but not toward hiding;
  - dismiss, including twice in a row;
  - remove cascading the item's reports;
  - each place in 3.2 leaving out a hidden recipe;
  - hidden comments in lists;
  - interactions refused on hidden items.
- `ContentReportRepositoryDbTest` checks the new table, its constraints and the
  queue query on real Postgres. It skips until V15 is live, using the same
  `pg_constraint` check as the earlier DbTests.
- Frontend tests cover:
  - the report dialog with its success and three error messages;
  - Report hidden on your own content and for signed-out visitors;
  - the Reports queue with Remove, Dismiss and the "already handled" message;
  - the redirects from the old review links;
  - the author banners;
  - the form wording;
  - the Privacy and Terms text.
- A browser check with made-up data, deleted afterwards, at desktop and phone
  width in light and dark.

## 8. Review focus

1. A hidden recipe leaking into a list that section 3.2 misses, such as a
   cookbook, saved list, author page or search.
2. Two people sending the third report at the same moment: the item must hide
   once, with no duplicate report rows.
3. An admin dismissing while a new report arrives: the result must be
   consistent and the new report must stay open.
4. An author editing a hidden recipe: it must stay hidden.
5. A comment hidden on a recipe that is itself hidden or removed.

## 9. Not in this sub-project

- Video posts and the feed (sub-project 2), and follows (sub-project 3).
- Telling reporters what happened to their report.
- Reporting user profiles.
- Automatic actions beyond hiding at 3 reports, such as banning users.
- A rate limit on publishing recipes.
