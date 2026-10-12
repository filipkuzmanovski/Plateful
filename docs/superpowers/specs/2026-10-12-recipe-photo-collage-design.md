# Recipe photo collage and source-field removal: design

Date: 2026-10-12
Status: approved in conversation; this written spec is awaiting review.

## 1. Goal

People who write recipes on Plateful should be able to upload several photos of their own cooking from their phone or computer. Those photos then show as a collage on the recipe page.

The "Source name" and "Source URL" fields should leave the create/edit form, because a recipe written on Plateful isn't imported from anywhere.

Success looks like this:

- A signed-in user picks up to 6 photos from their device while creating or editing a recipe.
- They can reorder or remove those photos, and the first one is the cover.
- The recipe page shows the photos as a collage, and tapping a photo opens a full-screen viewer.
- Browse cards show the cover.
- Users can't set "Source name" or "Source URL", in the form or through the API.
- Imported Spoonacular recipes look and behave exactly as before.

## 2. Decisions from the conversation

| Question | Decision |
|---|---|
| How are photos added? | Upload from the device. |
| How many, and which is the cover? | Up to 6. The first photo is the cover. |
| Keep the "Image URL" paste field? | No. Uploads only. Imported recipes keep their existing image. |
| Where do uploads go? | The browser uploads straight to Supabase Storage. The backend validates and stores the ordered list (approach A). |
| Collage layout | Claude's design (section 6). If it doesn't land, we do a side-by-side of several options afterwards. |

## 3. Supabase Storage (one-time setup)

Commit an SQL file at `docs/supabase/recipe-photos-storage.sql`. It is run once in the Supabase SQL editor by the user, or by Claude only after the user confirms, because it changes the live project. It is NOT a Flyway migration: Flyway owns the `public` schema only.

The file:

- Creates the bucket `recipe-photos`:
  - `public = true` (anyone can view photos).
  - `file_size_limit = 5242880` (5 MB).
  - `allowed_mime_types = {image/jpeg, image/png, image/webp}`.
- Creates policies on `storage.objects`, all scoped to `bucket_id = 'recipe-photos'`:
  - **INSERT**, for role `authenticated`, only when `(storage.foldername(name))[1] = auth.uid()::text`.
  - **DELETE**, for role `authenticated`, with the same folder condition.
  - **No UPDATE policy.** Files are never overwritten; a changed photo is a new file.
  - **SELECT**, for role `authenticated`, with the same folder condition. Public URL reads need no policy (the bucket is public), but Storage `remove()` checks SELECT as well as DELETE, so without this policy cleanup silently deletes nothing.
- Is idempotent: it uses `on conflict (id) do update` for the bucket settings and `drop policy if exists` before each `create policy`, so running it twice is safe.

Object path format: `{userId}/{uuid}.{ext}`, where `ext` is `jpg`, `png` or `webp`.

## 4. Backend

### 4.1 Schema: Flyway `V3__recipe_photos.sql`

```sql
CREATE TABLE recipe_photos (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipe_id UUID NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    path      TEXT NOT NULL,
    position  SMALLINT NOT NULL CHECK (position BETWEEN 0 AND 5),
    UNIQUE (recipe_id, position)
);
CREATE INDEX idx_recipe_photos_recipe ON recipe_photos(recipe_id);
```

- The table stores the storage path, not the full URL. The URL is built from configuration, so moving the project only changes one property.
- Use a JPA entity `RecipePhoto`, mapped from `Recipe` as an ordered `@OneToMany` (`orphanRemoval = true`, `@OrderBy("position")`), or an equivalent the plan picks. Replacing the list on update must delete the old rows.

### 4.2 Configuration

- New property `app.storage.photos-public-base-url`. Its value is `https://bwnbvtbhcmeifsmlvmgl.supabase.co/storage/v1/object/public/recipe-photos`.
- A photo's public URL is `base + "/" + path`.
- `application.properties` contains the user's own uncommitted edits. Stage only the new line, with a trimmed patch and `git apply --cached` (the same method as ruling R1 of the Golden Hour plan).

### 4.3 Create and update input (`RecipeCreateDto`)

- Remove `image`, `sourceName` and `sourceUrl` from the DTO.
  - Spring's Jackson setup ignores unknown JSON properties, so an old client still sending them is silently ignored, not rejected.
  - Check before removing: the Spoonacular importer must not depend on these DTO fields. It builds entities through its translator; the plan verifies this.
- Add `@Size(max = 6) List<@NotBlank @Size(max = 200) String> photos`. A null list counts as empty.
- The service validates the list before saving. Any failure is a 400 in the existing validation shape: `errors = {"photos": "<message>"}`. The rules:
  - Every path matches `^{callerId}/[0-9a-fA-F-]{36}\.(jpg|png|webp)$`, where `callerId` is the authenticated user's id. This means nobody can attach another user's file, a random link, or a path that escapes the folder.
  - No duplicates.
  - At most 6 entries. The DTO checks this too, but the service re-checks it.
- On update, the owner check comes first, as it does for cuisines. A stranger always gets 403, never a photo-validation message.
- On create and update:
  - The service replaces the recipe's photo rows with the given list, with positions 0..n-1.
  - It sets `recipe.image` to the public URL of `photos[0]`, or to null when the list is empty.
  - It sets `sourceName` and `sourceUrl` to null for user recipes.
- The service does not check that the files actually exist in storage. A bad path just shows a broken image for its owner, which is acceptable. Storage policies already stop anyone writing to someone else's folder.

### 4.4 Read output (`RecipeDisplayDto`)

- Add `List<String> photos`, the public URLs in position order.
- When a recipe has no photo rows but has `image`, which is the case for imported Spoonacular recipes, `photos = [image]`. The frontend therefore always renders from `photos`.
- `image` stays in the DTO, unchanged. It is the cover used by cards and lists.
- Fetching photos must not add one query per recipe on list endpoints. Either:
  - list endpoints don't load photos (cards only need `image`), and `photos` is built only for the single-recipe endpoint; or
  - they're batch-loaded.
  The plan picks one and keeps list query counts unchanged.

### 4.5 Unchanged

- `recipes.image`, `source_name` and `source_url` stay in the schema.
- Imported recipes keep their values.
- The detail page's "From {sourceName}" credit still shows for imported recipes.

## 5. Frontend: the form (`RecipeFormPage`)

- Remove the "Image URL" field and its preview, plus "Source name" and "Source URL", from the form, the zod schema, the defaults and the request body.
- Add a **Photos** section with a new component, `features/authoring/PhotoPicker.tsx`.
  - It has an "Add photos" button wrapping `<input type="file" multiple accept="image/jpeg,image/png,image/webp">`. The phone gallery opens through the normal file picker.
  - **Client-side checks, before uploading:**
    - The file type must be one of the three allowed.
    - The total can't go past 6 photos. Extra files are refused with a message such as "You can add up to 6 photos."
  - **Shrinking:** each accepted file is drawn to a canvas and scaled down so its longest side is at most 2000 px. It is re-encoded as WebP at about 0.85 quality, falling back to JPEG if the browser can't encode WebP. A result still over 5 MB is refused with a message.
  - **Upload:** `supabase.storage.from('recipe-photos').upload('{userId}/{crypto.randomUUID()}.{ext}', blob, { contentType, upsert: false })`. This lives in a small module, `src/api/photos.ts` (`uploadPhoto`, `deletePhotos`, `publicPhotoUrl`), so components never call supabase directly. Update the "Auth ONLY" comment in `supabaseClient.ts` to "Auth and photo storage only".
  - **Tiles:** each photo is a thumbnail tile.
    - While uploading, it shows a spinner.
    - Once uploaded, it shows a Remove button (labelled "Remove photo N"), plus "Move photo N left" and "Move photo N right" buttons.
    - The first tile has a "Cover" pill.
    - A failed upload shows an error state on the tile, with "Retry" and "Remove".
  - **Form value:** the ordered list of storage paths, wired in through react-hook-form `Controller`, the same way as the cuisine picker.
  - **Save** is disabled while any upload is pending or failed, with a short hint saying why.
  - **On the edit page:** existing photos load as tiles from the recipe's `photos`. Their paths are derived by stripping the configured public base URL. An imported-style image that isn't in our bucket can't be in a user recipe, because user recipes only ever get bucket photos.
- **After a successful save:**
  - On edit, the frontend deletes the storage files for paths that were removed (`deletePhotos`).
  - On recipe delete, after the DELETE call succeeds, it deletes all of that recipe's photo files.
  - A failed cleanup is logged to the console and never shown as an error, because the recipe change itself succeeded.
- **Known gap, logged as a follow-up rather than built:** files uploaded on a form that is then abandoned stay in storage.

## 6. Frontend: the collage (`RecipeDetailPage`)

New component: `features/recipe/PhotoCollage.tsx`. It takes `photos: string[]` and the recipe title for alt text ("{title}, photo N of M").

Layout, from 640px wide upwards:

| Photos | Layout |
|---|---|
| 0 | The existing no-image placeholder. |
| 1 | One large image, as today (4:3). |
| 2 | Two equal images side by side. |
| 3 or more | The cover on the left (2/3 width, full height), with photos 2 and 3 stacked on the right. With more than 3, the third tile has an overlay reading "+N", where N is the number of photos not shown. |

Below 640px: the cover is full width, with a horizontal row of square thumbnails of the remaining photos underneath it.

**Viewer:** clicking or tapping any tile opens `PhotoViewer`, a Radix Dialog.

- It shows the full image, a "2 / 5" counter, Previous and Next buttons (lucide chevrons, labelled), and a Close button.
- The left and right arrow keys navigate, and Escape closes.
- Focus returns to the tile that opened it.
- With a single photo, the arrows are hidden.

Styling follows Golden Hour:

- The collage uses rounded `--radius-card` corners and a `ring-sand` outline.
- Images are `object-cover`.
- The viewer's backdrop is `bg-olive/80`.
- No emojis.
- All tiles are `<button>`s, so they get the pointer cursor and keyboard focus.

Cards (`RecipeCard`) don't change and keep using `image`.

## 7. Testing

**Backend (TDD):**

- `RecipeService` create:
  - Valid paths are stored in order.
  - `image` is the URL of the first photo.
  - The source fields end up null even when the request carries them in its JSON.
- Rejections with a 400 on the `photos` key:
  - A path in another user's folder.
  - A malformed path (`../x`, a full `https://` URL, the wrong extension).
  - Duplicate paths.
  - More than 6 paths.
- Update:
  - Replaces the list, and its positions are rewritten.
  - An empty list clears `image`.
  - A non-owner gets 403 before photo validation.
- Display:
  - A user recipe's photos come out in order.
  - An imported recipe without rows falls back to `[image]`.
  - A recipe with neither gives `[]`.
- Controller: the JSON shape of `photos` on the single-recipe response.
- A real-database test (`@Tag("db")`, the same config as the existing DB tests so the Spring context is shared): the V3 table exists, and the photo query runs. This can only run after the user's backend has applied V3.

**Frontend (Vitest, RTL and MSW, with a mocked supabase storage):**

- PhotoPicker:
  - Adding files uploads them and fills tiles in order.
  - A seventh file is refused with a message.
  - A wrong type is refused.
  - Remove and move work, and the cover pill follows position 0.
  - Save is disabled while an upload is pending.
  - A failed upload shows Retry.
- RecipeFormPage:
  - There is no Source name, Source URL or Image URL field.
  - The request body carries `photos` and no `image`, `sourceName` or `sourceUrl`.
  - Editing an existing recipe pre-fills its photo tiles.
  - Removing a photo on edit calls `deletePhotos` after the save.
- PhotoCollage, at 1, 2, 3 and 6 photos:
  - The correct number of tiles.
  - "+3" on the third tile when there are 6.
  - Alt texts.
- PhotoViewer:
  - Opens on the clicked photo.
  - Next and Previous, and the arrow keys, move between photos.
  - The counter updates.
  - Escape closes.
- `expectNoEmoji()` on the form and on the detail page.

Shrinking (canvas) isn't available in jsdom. The resize helper takes the encoder as an injectable function, so its decisions (target size, fallback) are unit-tested, and the browser-only canvas call is a thin wrapper.

## 8. Out of scope

- Cleaning up files left behind by abandoned forms (logged as a follow-up).
- Drag-and-drop reordering. Arrow buttons cover it accessibly.
- Captions per photo.
- Editing or cropping photos.
- Photos on imported recipes.
- Changing cards to show more than the cover.

## 9. Rollout order

1. The user runs (or approves running) `docs/supabase/recipe-photos-storage.sql`.
2. Merge the backend. On restart, the user's backend applies V3 automatically through Flyway.
3. Merge the frontend.

Steps 2 and 3 ship on one branch. Until step 1 is done, uploads fail with a storage error on the tile, and the rest of the form still works.
