# Recipe video: design

Date: 2026-10-19
Status: approved in conversation; this written spec is awaiting review.

## 1. Goal

Authors can attach one short video to a recipe: up to 60 seconds and 50 MB, for example the finished dish or a quick technique. It plays inside the existing photo collage. It goes through admin review like the rest of the recipe, and it stays within the Supabase free plan.

## 2. Decisions from the conversation

| Question | Decision |
|---|---|
| Kind of video | A short clip: up to 60 s and 50 MB, one per recipe. Uploaded, not linked. |
| Where it shows | Inside the photo collage, always the **second** tile, after the cover photo. With no photos, the video tile becomes the cover. |
| Photo slots | Doesn't use one: 6 photos plus 1 video. |
| Formats | MP4, WebM and MOV are accepted. Before upload the browser test-plays the file, and anything it can't play is refused. There is no transcoding. |
| Still frame | Captured in the browser at upload time and stored as a JPEG. Tiles show it before playback. |
| Autoplay | Yes. The collage tile plays the clip **muted, looping and inline while it is on screen** (details in §5.3). Opening the viewer plays it with sound and controls. |
| Upload feedback | A spinner with "Uploading video…". No percentage. |

## 3. Storage

A new Supabase Storage bucket, `recipe-videos`, is set up by `docs/supabase/recipe-videos-storage.sql`. The user runs that file once in the SQL editor. It is not a Flyway migration, the same as `recipe-photos-storage.sql`.

- **Access:**
  - public, so it can be read through public URLs;
  - `file_size_limit = 52428800` (50 MB);
  - `allowed_mime_types = ['video/mp4', 'video/webm', 'video/quicktime', 'image/jpeg']`.
- **Policies:** the same three own-folder policies as photos, for `authenticated` users. They are INSERT, SELECT (required for deletes) and DELETE, each where `bucket_id = 'recipe-videos'` and `(storage.foldername(name))[1] = auth.uid()::text`. There is no UPDATE policy.
- **Paths:**
  - clip: `{userId}/{uuid}.{mp4|webm|mov}`;
  - still frame: `{userId}/{uuid}.jpg`, with its own UUID.
- **Backend URL property:** `app.storage.videos-public-base-url=https://bwnbvtbhcmeifsmlvmgl.supabase.co/storage/v1/object/public/recipe-videos`, next to the photos property.
- **Frontend:** derives the bucket's public URL from `VITE_SUPABASE_URL`, the same way `api/photos.ts` does.

## 4. Backend

### 4.1 Flyway `V7__recipe_video.sql`

```sql
ALTER TABLE recipes
    ADD COLUMN video_path             TEXT,
    ADD COLUMN video_poster_path      TEXT,
    ADD COLUMN video_duration_seconds SMALLINT
        CHECK (video_duration_seconds BETWEEN 1 AND 60),
    ADD CONSTRAINT recipes_video_all_or_none CHECK (
        (video_path IS NULL AND video_poster_path IS NULL AND video_duration_seconds IS NULL)
        OR (video_path IS NOT NULL AND video_poster_path IS NOT NULL AND video_duration_seconds IS NOT NULL)
    );
```

The plan must confirm that the latest migration is V6.

### 4.2 API shape

**`RecipeCreateDto`** gains an optional `video` object, or `null`:

```json
{ "path": "{userId}/{uuid}.mp4", "posterPath": "{userId}/{uuid}.jpg", "durationSeconds": 42 }
```

**`RecipeDisplayDto`** gains `video`, which is null when the recipe has no video:

```json
{ "url": "<public clip URL>", "posterUrl": "<public poster URL>", "durationSeconds": 42 }
```

On update, the request carries the full desired state, the same as `photos`:
- `video` present: set or replace the video;
- `video` null or absent: remove it.

### 4.3 Validation: `RecipeVideoPaths`

`RecipeVideoPaths` sits beside `RecipePhotoPaths` in the `photo` package. Invalid input raises `InvalidVideoException`, which `GlobalExceptionHandler` maps to 400 with `errors: { video: "<message>" }`, in the same style as `InvalidPhotoException`.

| Rule | Message |
|---|---|
| `path` matches `^{userId}/[0-9a-f-]{36}\.(mp4|webm|mov)$` (the caller's own folder) | "Video must be an uploaded file in your folder." |
| `posterPath` matches `^{userId}/[0-9a-f-]{36}\.jpg$` | "Video poster must be an uploaded image in your folder." |
| `durationSeconds` is between 1 and 60 | "Videos can be up to 60 seconds long." |
| `path` and `posterPath` are present together (the DTO's nested fields are all required) | Bean validation 400 |

The backend never receives or inspects the video bytes. The 50 MB limit and the MIME types are enforced by the bucket.

### 4.4 Service

`RecipeService.create` and `update` both set or clear the three columns from the DTO.

- **Revision and review:** in `update`, a change to the video counts as a content change (add, replace or remove). It is covered by the existing rule that increments `revision` on every successful update, which already sends a non-admin's approved or rejected recipe back to `pending`. No new review logic is needed.
- **Cover image:** today `image` is the URL of `photos[0]`. With no photos and a video, `image` becomes the poster URL. With no photos and no video, it keeps today's behaviour: the legacy linked image is left alone, otherwise it is null.
- **Display:** `RecipeDisplayDto.video` is built from the columns with `RecipeVideoPaths.publicUrl(...)` for both URLs, in the per-recipe display assembly and in the batched page assembly. It adds no extra queries, because the columns live on `recipes`.
- **Unchanged paths:** visibility, the saved stubs and admin review need no changes. Hidden recipes never reach a viewer, and stubs carry no media.

## 5. Frontend

### 5.1 API and types

- **`src/api/videos.ts`:**
  - `VIDEO_BUCKET = 'recipe-videos'`, `MAX_VIDEO_BYTES = 52_428_800`, `MAX_VIDEO_SECONDS = 60`;
  - `ACCEPTED_VIDEO_TYPES = ['video/mp4', 'video/webm', 'video/quicktime']`;
  - `publicVideoUrl(path)`, `pathFromPublicVideoUrl(url)`;
  - `uploadVideo(userId, file) → path`, `uploadPoster(userId, blob) → path`;
  - `deleteVideoFiles(paths)`, best-effort with a warning on failure, the same as `deletePhotos`.
- **Types:**
  - `RecipeDisplay.video?: { url: string; posterUrl: string; durationSeconds: number } | null`;
  - `RecipeCreate.video?: { path: string; posterPath: string; durationSeconds: number } | null`.
- **`src/lib/videoProbe.ts`** holds the only browser-media code, so tests can replace it with a fake:
  - `probeVideo(file): Promise<{ playable: true; durationSeconds: number } | { playable: false }>` loads the file into a detached `<video>` element through an object URL and waits for `loadedmetadata`. It counts as unplayable on `error`, on a zero, infinite or NaN duration, or after a 15 s timeout.
  - `capturePoster(file, atSeconds): Promise<Blob>` seeks to `min(1, duration / 2)`, draws to a canvas (longest side at most 1280 px) and returns a JPEG blob at quality 0.85.

### 5.2 Recipe form: the `VideoPicker` field

The field sits below the photo picker, with the label "Video (optional)" and a button "Add video".

**The flow when a file is picked:**
1. If the type isn't accepted or the size is over the limit: "Videos must be MP4, WebM or MOV, up to 50 MB."
2. `probeVideo`:
   - not playable: "This video can't be played in most browsers. Please export it as MP4 and try again.";
   - duration over 60 s, after rounding up: "Videos can be up to 60 seconds long.".
3. `capturePoster`, then upload the clip and the poster. During the upload the field shows a spinner with "Uploading video…", and the form's Save button is disabled.
4. **On success:** the field shows the poster, the duration as `m:ss`, a "Play" preview button (opens the clip in the same viewer used on the recipe page) and the buttons "Replace" and "Remove".
5. **On upload failure:** "Couldn't upload the video. Please try again.". Any half-uploaded file of the pair is deleted on a best-effort basis.

**File lifecycle:**
- **Editing:** the form starts from the recipe's existing video, converting its URLs back to paths.
- **Save:** sends `video` with the rest of the form.
- **After a successful save:** files that were in the original recipe but aren't in the saved state are deleted, on a best-effort basis. The same goes for files uploaded during this session that ended up unused, for example replaced twice.
- **Cancelling an edit** never deletes the original's files.
- **New uploads on a form that is abandoned** stay in storage. This is the existing known gap shared with photos.

The existing "Saving changes sends this recipe back for review" notice already covers video changes. No new copy is needed.

### 5.3 Collage and viewer

**The `PhotoCollage` tile:**
- `PhotoCollage` receives `video` next to `photos`. The media order is: cover photo, then the video, then the remaining photos. With no photos, the video is the cover.
- The video tile is a `<button>` with the label "Play video, {n} seconds".
- It shows the poster as the background, a round play icon in the centre and an `m:ss` duration chip in a corner.

**Autoplay** (the user's decision):
- The tile renders a `<video>` with `muted`, `loop`, `playsInline`, `preload="none"` and `poster={posterUrl}`.
- An `IntersectionObserver` calls `play()` when at least 50% of the tile is visible, and `pause()` when it leaves.
- **Reduced motion:** when `prefers-reduced-motion: reduce` matches, there is no autoplay; the tile shows only the poster.
- **Rejected `play()`:** if the browser rejects `play()` (an autoplay policy), the tile silently stays on the poster.
- Nothing downloads before the tile is on screen.

**The viewer** (`PhotoViewer`):
- Opening the video tile opens the viewer at the video's position. The arrows follow the media order.
- At the video position it renders `<video controls autoPlay playsInline>`, with sound on (the user just clicked) and no loop.
- Navigating away, or closing the viewer, pauses it.
- If the clip fails to load: "This video couldn't be played."

### 5.4 Cards and other surfaces

- **`RecipeCard`:** when `recipe.video` is set, the card shows a small "Video" badge (a camera icon plus the word) in the top-left corner. The bookmark is top-right. There is no autoplay on cards.
- **No-photo recipes** with a video get `image` set to the poster (§4.4), so cards show it with no frontend change.
- **The admin review page and the author's own page** use the same `RecipeBody` / `PhotoCollage`, so the video shows there too.
- **Privacy Policy and Terms:** "photos" becomes "photos and videos" in these places:
  - the Privacy Policy's "content you post" line;
  - its public-visibility line;
  - its Supabase storage line ("photo and video storage");
  - the Terms line about not posting copied recipes or photos.

### 5.5 Style

Golden Hour tokens, no emojis (`expectNoEmoji()`), and real buttons for every control.

## 6. Testing

### Backend
- **DB test** (the same annotations as `RecipeCuisineQueryDbTest`, rolled back):
  - after V7, the three columns are null on every pre-existing recipe;
  - inserting a partial video, for example a path without a poster, violates `recipes_video_all_or_none`.
- **`RecipeVideoPathsTest`:**
  - own folder, a foreign folder, a bad extension, a poster that isn't a JPEG, a malformed UUID;
  - durations 0, 1, 60 and 61.
- **Service tests:**
  - create with a video stores it and returns `video` with both public URLs and the duration;
  - update adding, replacing and removing the video;
  - each change increments `revision`, and a non-admin's approved recipe goes to `pending`;
  - with no photos and a video, `image` is the poster URL;
  - removing the video clears all three columns.
- **Controller test:** an invalid video gets 400 with `$.errors.video`.

### Frontend
`videoProbe` is mocked in every test.
- **`VideoPicker`:**
  - the type and size message;
  - the can't-play message;
  - the too-long message;
  - the spinner, with Save disabled during the upload;
  - success shows the poster, the duration and the Replace and Remove buttons;
  - an upload failure shows its message;
  - Remove then Save sends `video: null` and deletes the files only after the save;
  - cancelling an edit deletes nothing.
- **`PhotoCollage`:**
  - the video is the second tile, or the cover with no photos;
  - its label and duration chip;
  - the tile video is `muted`, `loop`, `playsInline` and `preload="none"`, plays when the IntersectionObserver reports it visible (mocked), and pauses when it isn't;
  - no autoplay under `prefers-reduced-motion`;
  - the viewer's arrow order;
  - pausing on navigate and close;
  - the error message.
- **`RecipeCard`:** the "Video" badge.
- **Content pages:** the updated wording.
- **`expectNoEmoji()`** throughout.

### Manual browser check (by the controller, at the end)
Use the user's running site at localhost:5173: upload a short MP4, check the autoplaying muted tile, the viewer with sound, and the card badge.

## 7. Rollout

1. Run `docs/supabase/recipe-videos-storage.sql` once in the Supabase SQL editor.
2. V7 applies when the backend restarts. During development it lands when the dev backend restarts.
3. No new environment variables or dependencies.

## 8. Out of scope

- Transcoding or compression.
- More than one video per recipe.
- An upload progress percentage.
- YouTube or TikTok links.
- Autoplay on recipe cards.
- Cleanup of files from forms that were never saved (the existing known gap).
- A storage-quota guard.
