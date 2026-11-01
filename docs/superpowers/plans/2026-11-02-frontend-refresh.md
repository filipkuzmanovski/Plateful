# Frontend refresh ("Cookbook") Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle every Plateful screen in the "Cookbook" direction with role-named colour tokens, add
dark mode (Match my device / Light / Dark), and fix the contrast, text-size, phone and photo problems
from the review.

**Architecture:** Task 1 adds the new tokens, fonts and the theme machinery. The old token names stay as
aliases of the new ones, so every intermediate commit renders coherently in both modes. Tasks 2 to 9
restyle one group of screens each, moving its classes to the new names (spec 4.1 mapping table). Task 10
deletes the aliases and old shadows, checks nothing old is left, verifies in the browser and writes the
dev log.

**Tech Stack:** React 19, TypeScript, Vite, Tailwind v4 (`@theme` in `src/index.css`), lucide-react,
Radix Dialog/Select, sonner, Vitest, React Testing Library, MSW.

**Spec:** `docs/superpowers/specs/2026-11-02-frontend-refresh-design.md`. Read it before every task. Its
sections 4.1 (tokens and the mapping table), 4.2 (type), 4.3 (shape) and 6 (shared components) apply to
every task; section 7 says what each screen becomes.

## Global Constraints

- All commands run from `recipe-app-frontend/`.
- Before every commit: `npx vitest run` and `npm run build` both green. The baseline is 58 files and 708
  tests; counts only go up.
- Commits: stage explicit paths only. Never stage `tools/dataset/README.md`. Messages end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Frontend only. No backend changes.
- No emojis anywhere in the UI. Screen tests keep calling `expectNoEmoji()` from `@/test/noEmoji`.
- Do not change behaviour, ARIA roles and attributes, keyboard handling, live regions, routes or query
  keys, except where a task says so.
- Colours come only from the tokens in spec 4.1. No literal colours (`#hex`, `text-white`,
  `bg-black`, `rgb()` in `className` or `style`), except the video viewer backdrop and the 2FA QR code
  background (see Task 8).
- Text is at least 13px (`text-[13px]`); number badges may be 12px. No `uppercase` labels. No
  single-word accents in headings.
- No hover translate or scale (`hover:-translate-y-*`, `group-hover:scale-*`, `active:scale-*`).
- Touch targets: buttons `min-h-11`; icon buttons, chips, pagination and reactions at least 40px
  (`size-10` / `min-h-10`).
- Inputs: `text-base sm:text-[15px]` (16px on phones stops the iOS zoom).
- Do not weaken a behaviour test. Class assertions that name old tokens are updated to the new names.

## Review Focus

1. **localStorage throws** (Safari private mode, blocked site data): the theme falls back to Match my
   device, nothing crashes, and choosing a mode still applies it for the session. Pinned by
   `theme.test.ts` "falls back to system when storage throws" and "applies a choice even when storage
   throws" (Task 1).
2. **A `matchMedia` stub without `addEventListener`**, or no `matchMedia` at all: ThemeProvider must not
   crash (ShareRecipe and videoCollage tests stub it). Pinned by `ThemeProvider.test.tsx` "renders when
   matchMedia is missing" (Task 1).
3. **The device switches to dark while the choice is Match my device**: the page follows without a
   reload; with Light or Dark chosen it does not. Pinned by `ThemeProvider.test.tsx` "follows the device
   while on system" and "ignores the device when a mode is chosen" (Task 1).
4. **Spoonacular URLs in other shapes** (`-556x370.jpg`, `.png`, no size suffix, `http:`) and
   non-Spoonacular URLs: `recipeImageSrcSet` returns a correct set or `undefined`, never a broken URL.
   Pinned by `recipeImage.test.ts` (Task 2).
5. **The 2FA QR code in dark mode**: authenticator apps cannot read light-on-dark codes, so the QR keeps a
   white quiet zone in both modes. Pinned by a `TwoFactorSettings.test.tsx` assertion that the QR `img`
   sits in an element with class `bg-white` (Task 8).

---

### Task 1: Tokens, fonts and theme machinery

**Files:**
- Modify: `package.json`, `package-lock.json` (add `@fontsource-variable/newsreader`,
  `@fontsource-variable/hanken-grotesk`; remove `@fontsource-variable/fraunces`,
  `@fontsource-variable/inter`)
- Modify: `src/index.css`, `index.html`, `src/test/setup.ts`, `src/components/AppShell.tsx` (wrap in
  `ThemeProvider` only)
- Create: `src/theme/theme.ts`, `src/theme/ThemeProvider.tsx`
- Test: `src/theme/theme.test.ts`, `src/theme/ThemeProvider.test.tsx`

**Interfaces:**
- Produces, `src/theme/theme.ts`:
  - `type ThemeChoice = 'system' | 'light' | 'dark'`
  - `type ResolvedTheme = 'light' | 'dark'`
  - `const THEME_STORAGE_KEY = 'plateful.theme'`
  - `readThemeChoice(): ThemeChoice`: an invalid, missing or unreadable value gives `'system'`.
  - `writeThemeChoice(choice: ThemeChoice): void`: `'system'` removes the key; it swallows storage
    errors.
  - `systemPrefersDark(): boolean`: `false` when `matchMedia` is missing.
  - `resolveTheme(choice: ThemeChoice, prefersDark: boolean): ResolvedTheme`
  - `applyTheme(resolved: ResolvedTheme): void`: sets `document.documentElement.dataset.theme` and
    `document.documentElement.style.colorScheme`.
- Produces, `src/theme/ThemeProvider.tsx`:
  - `ThemeProvider({ children })`
  - `useTheme(): { choice: ThemeChoice; resolved: ResolvedTheme; setChoice(c: ThemeChoice): void }`,
    which throws outside the provider.
- Produces, `src/index.css`:
  - Utilities for every spec 4.1 token (`bg-page`, `bg-surface`, `bg-tint`, `text-ink`,
    `text-ink-soft`, `border-rule`, `border-field`, `text-accent`, `bg-accent`, `text-accent-strong`,
    `text-accent-ink`, `text-on-accent`, `text-on-ink`, `text-danger`, `text-on-danger`, `bg-scrim`).
  - `shadow-float`, `rounded-card` (0.75rem), and fonts `font-serif` (Newsreader) and `font-sans`
    (Hanken Grotesk).
  - **Temporary aliases** (deleted in Task 10): `--color-olive: var(--color-ink)`,
    `--color-cream: var(--color-page)`, `--color-paper: var(--color-surface)`,
    `--color-sand: var(--color-tint)`, `--color-muted: var(--color-ink-soft)`,
    `--color-saffron: var(--color-accent)`, `--color-saffron-deep: var(--color-accent-ink)`,
    `--color-terracotta: var(--color-accent-ink)`, `--color-terracotta-deep: var(--color-accent-ink)`,
    `--color-sand-deep: var(--color-field)`. Old `shadow-card`, `shadow-lift` and `shadow-button` stay
    until Task 10.

- [ ] **Step 1: Write the failing tests**

`src/theme/theme.test.ts` (use `vi.spyOn(Storage.prototype, 'getItem' | 'setItem' | 'removeItem')` to
throw, and `vi.stubGlobal('matchMedia', ...)`):
- `resolveTheme`: `('system', true)` gives `'dark'`, `('system', false)` gives `'light'`,
  `('light', true)` gives `'light'`, `('dark', false)` gives `'dark'`.
- "reads a stored choice": stored `'dark'` gives `'dark'`; stored `'purple'` gives `'system'`.
- "falls back to system when storage throws": `getItem` throws, so the result is `'system'`.
- "writes and clears": `writeThemeChoice('light')` stores `'light'`; `writeThemeChoice('system')`
  removes the key; a throwing `setItem` does not throw out.
- "applies the resolved theme": `applyTheme('dark')` gives `dataset.theme === 'dark'` and
  `style.colorScheme === 'dark'`.
- "systemPrefersDark is false without matchMedia".

`src/theme/ThemeProvider.test.tsx` (a small consumer component that shows `choice` and `resolved` and
has buttons calling `setChoice`):
- "starts from the stored choice and applies it": stored `'dark'` gives `data-theme="dark"` on
  `<html>` after render.
- "follows the device while on system": a `matchMedia` stub whose `addEventListener('change', fn)`
  captures `fn`; flip `matches` to true and call `fn`, and `data-theme` becomes `dark`.
- "ignores the device when a mode is chosen": after `setChoice('light')`, firing the change keeps
  `light`.
- "applies a choice even when storage throws".
- "syncs from another tab": dispatch `new StorageEvent('storage', { key: 'plateful.theme', newValue:
  'dark' })`, and `choice` becomes `dark`.
- "renders when matchMedia is missing": `vi.stubGlobal('matchMedia', undefined)` renders without
  throwing.
- "useTheme throws outside the provider".

- [ ] **Step 2: Run them to verify they fail**

Run: `npx vitest run src/theme`
Expected: FAIL (modules do not exist).

- [ ] **Step 3: Implement `theme.ts` and `ThemeProvider.tsx`**

ThemeProvider details:
- It keeps `choice` in state (initialised with `readThemeChoice()`) and applies
  `resolveTheme(choice, systemPrefersDark())` in a layout effect.
- It subscribes with `mql?.addEventListener?.('change', ...)` only while `choice === 'system'`.
- It listens to `window` `storage` events for `THEME_STORAGE_KEY`.
- `setChoice` writes and sets state.

- [ ] **Step 4: Fonts, tokens and the first-paint script**

- `npm uninstall @fontsource-variable/fraunces @fontsource-variable/inter` and
  `npm install @fontsource-variable/newsreader @fontsource-variable/hanken-grotesk`.
- In `src/index.css`:
  - Import `@fontsource-variable/hanken-grotesk`, `@fontsource-variable/newsreader/opsz.css` and
    `@fontsource-variable/newsreader/opsz-italic.css`.
  - Set `--font-sans: 'Hanken Grotesk Variable', ui-sans-serif, system-ui, sans-serif` and
    `--font-serif: 'Newsreader Variable', ui-serif, Georgia, serif`.
  - Add the spec 4.1 light values in `@theme`, and the dark values in
    `@layer base { :root[data-theme='dark'] { ... } }`.
  - Add `--shadow-float: 0 1px 2px rgb(0 0 0 / 0.06), 0 12px 32px -8px rgb(0 0 0 / 0.22)` and the
    aliases listed in Interfaces.
  - The body becomes `bg-page text-ink font-sans` with the radial-gradient background removed.
  - `::selection` uses `color-mix(in oklab, var(--color-accent) 35%, transparent)`.
  - The `:focus-visible` outline uses `var(--color-ink)`.
- In `index.html`:
  - Add `<meta name="color-scheme" content="light dark" />`.
  - Add `<meta name="theme-color" media="(prefers-color-scheme: light)" content="#fbfbf7" />` and the
    dark one with `#161a14`.
  - Add this script in `<head>` before any stylesheet:

```html
<script>
  (function () {
    var c = null;
    try { c = localStorage.getItem('plateful.theme'); } catch (e) {}
    var dark = c === 'dark' || (c !== 'light' && !!window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
  })();
</script>
```

- In `src/test/setup.ts`, before the server hooks, add `window.matchMedia ??= (query) => ({ matches:
  false, media: query, onchange: null, addEventListener() {}, removeEventListener() {},
  addListener() {}, removeListener() {}, dispatchEvent: () => false })` with the right cast.
- Also add `afterEach(() => { localStorage.clear(); delete document.documentElement.dataset.theme; })`.
- In `AppShell.tsx`, wrap the returned tree in `<ThemeProvider>`. Nothing else in AppShell changes in
  this task.

- [ ] **Step 5: Run everything**

Run: `npx vitest run` then `npm run build`
Expected: all pass, the build is clean. `git grep -n "fraunces\|'Inter" -- src index.html` finds nothing.

- [ ] **Step 6: Commit**

```bash
git add package.json package-lock.json index.html src/index.css src/test/setup.ts src/components/AppShell.tsx src/theme
git commit -m "feat(frontend): Cookbook tokens, fonts and theme machinery"
```

---

### Task 2: Shared primitives, recipe cards and photo srcset

**Files:**
- Modify: `src/components/ui.tsx`, `src/components/RecipeCard.tsx`, `src/components/StarDisplay.tsx`,
  `src/components/Pagination.tsx`, `src/components/SelectMenu.tsx`,
  `src/components/ConfirmDialog.tsx`, `src/features/saved/BookmarkButton.tsx`,
  `src/features/review/StatusBadge.tsx`, plus every call site of `Pill tone=` and
  `Chip tone=` (find them with `git grep -n "tone=" src`)
- Modify (grid classes around `<RecipeCard>` only): `src/features/browse/BrowsePage.tsx`,
  `src/features/profile/MyProfilePage.tsx`, `src/features/profile/PublicProfilePage.tsx`,
  `src/features/saved/SavedRecipesPanel.tsx`
- Create: `src/lib/recipeImage.ts`
- Test: `src/lib/recipeImage.test.ts`

**Interfaces:**
- Consumes: the Task 1 tokens.
- Produces:
  - `Pill` tones `'tint' | 'accent' | 'ink' | 'danger'` (default `'tint'`). These replace `'sand'`,
    `'saffron'`, `'olive'` and `'terracotta'`: saffron becomes accent, sand becomes tint, olive
    becomes ink, and terracotta becomes accent.
  - `Chip` tones `'ink' | 'accent'` (default `'ink'`), replacing `'olive'` and `'saffron'`.
  - `recipeImageSrcSet(url: string | null | undefined): string | undefined`
  - `const CARD_IMAGE_SIZES = '(min-width: 1024px) 270px, 50vw'`
  - `const DETAIL_IMAGE_SIZES = '(min-width: 1024px) 460px, 100vw'`

- [ ] **Step 1: Write the failing test** `src/lib/recipeImage.test.ts`
  - `recipeImageSrcSet('https://img.spoonacular.com/recipes/1095838-312x231.jpg')` equals
    `'https://img.spoonacular.com/recipes/1095838-312x231.jpg 312w, https://img.spoonacular.com/recipes/1095838-480x360.jpg 480w, https://img.spoonacular.com/recipes/1095838-636x393.jpg 636w'`.
  - A `-556x370.png` URL gives the same three sizes with `.png`.
  - An `http://img.spoonacular.com/recipes/9-90x90.jpg` URL keeps `http:`.
  - `https://img.spoonacular.com/recipes/1095838.jpg` (no size) gives `undefined`.
  - A Supabase storage URL gives `undefined`. `null` gives `undefined`. `''` gives `undefined`.

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/lib/recipeImage.test.ts`
Expected: FAIL.

- [ ] **Step 3: Implement `recipeImageSrcSet`**

Use one regex anchored on `^(https?://img\.spoonacular\.com/recipes/[^/]+?)-\d+x\d+\.(\w+)$`.

- [ ] **Step 4: Restyle the primitives per spec section 6**

Button, Field, TextInput, SectionHeading, Spinner, Chip, Pill, EmptyState, StarDisplay (lit stars
`fill-accent text-accent`, unlit `text-field`), Pagination, the SelectMenu trigger and content,
ConfirmDialog (`bg-scrim` overlay), BookmarkButton (`bg-surface` circle, `size-10`) and StatusBadge.
Then update the `tone=` call sites.

- [ ] **Step 5: Restyle RecipeCard per spec section 6**

- No container.
- The image gets `srcSet={recipeImageSrcSet(recipe.image)}` and `sizes={CARD_IMAGE_SIZES}`.
- The hover overlay and the title underline use `group-hover` and `group-focus-visible`.
- The title gets `break-words` so long words wrap at two columns.
- Recipe grids become `grid grid-cols-2 gap-x-4 gap-y-8 lg:grid-cols-4`.

- [ ] **Step 6: Run everything**

Run: `npx vitest run` then `npm run build`
Expected: green. Fix any test that queried a removed wrapper by role only if the role is still
present. Do not loosen assertions.

- [ ] **Step 7: Commit**

Stage the files listed above and the call sites by path. Message:
`feat(frontend): Cookbook primitives, recipe cards and photo srcset`.

---

### Task 3: Header, theme menu, phone menu, footer, toaster

**Files:**
- Modify: `src/components/AppShell.tsx`, `src/components/SiteFooter.tsx`
- Create: `src/components/ThemeMenu.tsx`
- Test: `src/components/ThemeMenu.test.tsx`, `src/components/appShell.test.tsx`

**Interfaces:**
- Consumes: `useTheme()` from Task 1.
- Produces: `ThemeMenu()`, which takes no props and reads `useTheme()`.

- [ ] **Step 1: Write the failing tests**

`ThemeMenu.test.tsx` (render inside `ThemeProvider`):
- The button's accessible name is "Theme: Match my device" by default. It has
  `aria-haspopup="menu"` and `aria-expanded="false"`.
- Clicking it opens a `menu` with three `menuitemradio` items, "Match my device", "Light" and "Dark".
  "Match my device" has `aria-checked="true"` and focus starts on the checked item.
- ArrowDown moves focus to "Light". Enter chooses it, so `data-theme="light"` is set, the menu closes,
  focus returns to the button, and the name becomes "Theme: Light".
- Escape closes the menu and returns focus. An outside `mousedown` closes it.
- `expectNoEmoji()`.

`appShell.test.tsx` (use `renderApp` and the existing auth and profile fixtures and handlers, the way
`gatedToggle.test.tsx` sets up a signed-in user):
- Signed in as an admin with 3 pending: opening the user menu shows links "New recipe" and
  "Review queue, 3 pending", plus "My profile" and the "Sign out" button.
- Signed in as a non-admin: the user menu has no Review queue link.
- Signed out: a "Theme: Match my device" button and a "Sign in" link are present.

- [ ] **Step 2: Run them to verify they fail**

Run: `npx vitest run src/components/ThemeMenu.test.tsx src/components/appShell.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement ThemeMenu**

- Icons: `Monitor` for system, `Sun` for light, `Moon` for dark (lucide).
- The button is `size-10` with a fully rounded hover `bg-tint`.
- The menu is `bg-surface border border-rule rounded-card shadow-float`.
- Handle outside click and Escape the same way as the existing AppShell user menu.

- [ ] **Step 4: Restyle AppShell per spec section 6**

- The header, wordmark and nav underline as specified.
- The phone rules: Browse, New recipe and Review queue links are `hidden sm:inline-flex`; the extra
  user-menu items are `sm:hidden`.
- The menu's Review queue link reuses the `getPendingCount` query and keeps the "Review queue, N
  pending" name.
- Add ThemeMenu before the avatar and before Sign in.
- Toaster styles use `var(--color-surface)`, `var(--color-ink)` and `var(--color-rule)`.
- Keep every existing aria attribute.

- [ ] **Step 5: Restyle SiteFooter per spec section 6**

- [ ] **Step 6: Run everything and commit**

Run: `npx vitest run` then `npm run build`, both green. Commit the four files plus `ThemeMenu.tsx`.
Message: `feat(frontend): Cookbook header with theme menu and phone menu`.

---

### Task 4: Home and browse

**Files:**
- Modify: `src/features/browse/BrowsePage.tsx`, `src/features/ai/TodaysMeals.tsx`,
  `src/features/browse/SearchBar.tsx`, `src/features/browse/FilterDrawer.tsx`,
  `src/features/browse/ActiveFilterChips.tsx`, `src/features/browse/SeaCard.tsx`,
  `src/features/browse/CuisineAZ.tsx`
- Test: `src/features/ai/todaysMeals.test.tsx`, `src/features/browse/browse.test.tsx`

- [ ] **Step 1: Add the failing assertions**
  - `browse.test.tsx`: the page has heading level 1 "Find tonight's dinner".
  - `todaysMeals.test.tsx`: the three meal links sit inside one `list` (`getByRole('list')` within the
    Today's meals section), in the order Breakfast, Lunch, Dinner. Keep the existing assertions.

- [ ] **Step 2: Run them to verify they fail**

Run: `npx vitest run src/features/ai/todaysMeals.test.tsx src/features/browse/browse.test.tsx`
Expected: FAIL on the new heading and list.

- [ ] **Step 3: Restyle per spec section 7 "Home / browse"**

- Today's meals becomes an `<ol>` of rows; the existing `<ul>` of cards goes. Each row is one Link.
- The skeleton rows match.
- Filter drawer section titles and SeaCard `dt` labels lose `uppercase` and tracking.
- Use the new Chip tones.

- [ ] **Step 4: Run everything and commit**

Run: `npx vitest run` then `npm run build`, both green. Message: `feat(frontend): Cookbook home with
Today's meals menu`.

---

### Task 5: Recipe page and AI recipe view

**Files:**
- Modify: `src/features/recipe/RecipeBody.tsx`, `RecipeDetailPage.tsx`, `PhotoCollage.tsx`,
  `RatingBox.tsx`, `CommentsSection.tsx`, `ShareRecipe.tsx`, `VideoTile.tsx`, `HiddenRecipePage.tsx`
  (all in `src/features/recipe/`)
- Modify: `src/features/review/ReviewStatusBanner.tsx`, `src/features/saved/SaveRecipeButton.tsx`
- Modify: `src/features/ai/AiRecipeView.tsx`, `src/features/ai/AiRecipePage.tsx`,
  `src/features/ai/SaveAiRecipeButton.tsx`, `src/features/ai/SavedAiRecipes.tsx`

**Interfaces:**
- Consumes: `recipeImageSrcSet` and `DETAIL_IMAGE_SIZES` (Task 2), the `Pill` tones and
  `SectionHeading`.

- [ ] **Step 1: Restyle per spec section 7 "Recipe page" and "AI recipe"**

- The PhotoCollage main image gets `srcSet` and `sizes`.
- The viewer's black backdrop stays (allowed literal).
- Step numbers: `font-serif text-4xl text-accent-strong`, not italic, in a `w-10` column.
- Step and instruction text: `text-base leading-relaxed max-w-prose`.
- Reaction buttons: at least `min-h-10`.

- [ ] **Step 2: Run everything**

Run: `npx vitest run` then `npm run build`
Expected: green. `detail.test.tsx`, `adminRemoval.test.tsx`, `videoCollage.test.tsx`,
`ShareRecipe.test.tsx`, `aiRecipe.test.tsx` and `saveButtons.test.tsx` pass unchanged.

- [ ] **Step 3: Commit**

Message: `feat(frontend): Cookbook recipe page and AI recipe view`.

---

### Task 6: Cook and preferences

**Files:**
- Modify: `src/features/ai/CookPage.tsx`, `CookResult.tsx`, `PhotoScan.tsx`, `PhotoPicks.tsx`,
  `PostImport.tsx` (all in `src/features/ai/`)
- Modify: `src/features/preferences/IngredientChipInput.tsx`, `PreferencesForm.tsx`,
  `PreferencesPanel.tsx`, `PreferencesSetupPage.tsx` (all in `src/features/preferences/`)
- Test: `src/features/ai/cook.test.tsx`

- [ ] **Step 1: Add the failing assertion**

In `cook.test.tsx`, the selected tab has class `border-ink` and the other does not. This pins the
underline tab bar. Keep all tab keyboard tests.

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/features/ai/cook.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Restyle per spec section 7 "Cook" and "Preferences"**

- The tablist becomes `flex border-b border-rule max-sm:overflow-x-auto`.
- Each tab is `min-h-11 px-4 text-[15px] font-semibold border-b-2 -mb-px`. Active:
  `border-ink text-ink`. Inactive: `border-transparent text-ink-soft hover:text-ink`.
- The `PostImport` counter keeps `text-danger` exactly as tested.

- [ ] **Step 4: Run everything and commit**

Run: `npx vitest run` then `npm run build`, both green. Message: `feat(frontend): Cookbook cook page
and preferences`.

---

### Task 7: Recipe form and admin

**Files:**
- Modify: `src/features/authoring/RecipeFormPage.tsx`, `CuisinePicker.tsx`, `PhotoPicker.tsx`,
  `VideoPicker.tsx` (all in `src/features/authoring/`)
- Modify: `src/features/admin/ReviewQueuePage.tsx`, `ReviewRecipePage.tsx`, `AdminMfaPanel.tsx`,
  `ReasonDialog.tsx`, `RejectDialog.tsx`, `AdminRoute.tsx` (all in `src/features/admin/`)

- [ ] **Step 1: Restyle per spec section 7 "New and edit recipe" and "Admin"**

The dialogs use `bg-scrim` and `shadow-float`.

- [ ] **Step 2: Run everything**

Run: `npx vitest run` then `npm run build`
Expected: green. `authoring.test.tsx`, `PhotoPicker.test.tsx`, `videoPicker.test.tsx` and
`admin.test.tsx` pass unchanged.

- [ ] **Step 3: Commit**

Message: `feat(frontend): Cookbook recipe form and admin review`.

---

### Task 8: Profile, saved and two-factor settings

**Files:**
- Modify: `src/features/profile/MyProfilePage.tsx`, `PublicProfilePage.tsx`, `ProfileHeader.tsx`,
  `ProfileTabs.tsx`, `TwoFactorSettings.tsx`, `CompleteProfilePage.tsx` (all in
  `src/features/profile/`)
- Modify: `src/features/saved/SavedRecipesPanel.tsx`, `src/features/saved/SavedStubCard.tsx`
- Test: `src/features/saved/savedStubs.test.tsx`, `src/features/profile/TwoFactorSettings.test.tsx`,
  `src/features/profile/profileTabs.test.tsx`

- [ ] **Step 1: Update and add the assertions**
  - `savedStubs.test.tsx`: `toHaveClass('text-olive')` becomes `toHaveClass('text-ink')`, and
    `not.toHaveClass('text-olive/70')` becomes `not.toHaveClass('text-ink-soft')`.
  - `TwoFactorSettings.test.tsx`: in the enrol step, `screen.getByAltText('QR code for your
    authenticator app').parentElement` has class `bg-white`.
  - `profileTabs.test.tsx`: the selected tab has class `border-ink`.

- [ ] **Step 2: Run them to verify they fail**

Run: `npx vitest run src/features/saved/savedStubs.test.tsx src/features/profile`
Expected: FAIL.

- [ ] **Step 3: Restyle per spec section 7 "Profile"**

- The tabs use the same underline classes as Task 6.
- Wrap the QR `img` in `<span className="inline-block rounded-md bg-white p-3">` (allowed literal: QR
  readability).
- SavedStubCard "Unavailable" uses exactly `text-ink`.

- [ ] **Step 4: Run everything and commit**

Run: `npx vitest run` then `npm run build`, both green. Message: `feat(frontend): Cookbook profile,
saved and two-factor settings`.

---

### Task 9: Auth, content pages, error pages and About copy

**Files:**
- Modify: `src/features/auth/AuthCard.tsx`, `LoginPage.tsx`, `SignupPage.tsx`,
  `VerifyTwoFactorPage.tsx`, `BirthDateField.tsx` (all in `src/features/auth/`)
- Modify: `src/features/content/ContentPage.tsx`, `AboutPage.tsx`, `PrivacyPage.tsx`, `TermsPage.tsx`,
  `ContactPage.tsx` (all in `src/features/content/`)
- Modify: `src/components/ErrorBoundary.tsx`, `src/AppRoutes.tsx` (the `path="*"` not-found
  EmptyState, if it needs classes), `src/auth/IdleSessionGuard.tsx` (only if it has old-token classes)
- Test: `src/features/content/content.test.tsx`

- [ ] **Step 1: Add the failing assertions to `content.test.tsx`**

The About page lists:
- "Post your own recipes with photos and a video."
- "Cook with what you have: list your ingredients or scan your fridge, and get a new recipe made
  for you."
- "Turn a recipe post into your own version, adjusted to your food preferences."
- "See three meals made for you each day."

It no longer contains "up to 6 photos".

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/features/content/content.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Update the About list and restyle**

- Update the About list with exactly those sentences. Keep the other items.
- Restyle per spec section 7 "Auth", "Content pages" and "Error and hidden pages".
- The AuthCard medallion is removed. The aria-hidden wordmark is added.

- [ ] **Step 4: Run everything and commit**

Run: `npx vitest run` then `npm run build`, both green. Message: `feat(frontend): Cookbook auth and
content pages`.

---

### Task 10: Remove old tokens, verify, dev log

**Files:**
- Modify: `src/index.css` (delete the aliases and `--shadow-card`, `--shadow-lift`,
  `--shadow-button`), plus any file the grep below still finds
- Modify: `docs/plateful-frontend-dev-log.txt` (PART 21)

- [ ] **Step 1: Check that nothing old is left**

Run:
`git grep -nE "\b(olive|cream|paper|saffron|terracotta|sand|muted)(-deep)?(/[0-9]+)?\b" -- src ':!*.test.*'`
and
`git grep -nE "shadow-(card|lift|button)|uppercase|text-\[1[01]px\]|hover:-translate|scale-\[|#[0-9a-fA-F]{6}" -- src ':!*.test.*' ':!src/index.css'`

Expected: nothing except comments explaining history, and the allowed literals (the video viewer
backdrop, the QR `bg-white`). Fix anything else.

- [ ] **Step 2: Delete the aliases and old shadows from `src/index.css`**

Run `npx vitest run` and `npm run build`; both are green.

- [ ] **Step 3: Check in the browser**

- Start the dev server with `preview_start` `{name: "frontend"}`. If port 5173 is taken by the main
  checkout, ask the controller.
- Check at 375 and 1280px, in light and dark (ThemeMenu):
  - home, the filter drawer, a recipe page, login, signup and `/privacy`
  - signed-in `/cook` (both tabs), `/me` (both tabs), `/recipes/new` and `/admin/review`, only if the
    user has signed in in the browser pane themselves (never type credentials)
- What to look for:
  - no horizontal scroll at 375px, and long titles wrapping in the two-column grid
  - readable text in both modes, and a visible focus ring when tabbing
  - no flash of the wrong mode on reload in dark
  - no console errors
- Fix what is broken. Take one screenshot per mode of home and recipe for the report.

- [ ] **Step 4: Write the dev log PART 21**

Append PART 21 to `docs/plateful-frontend-dev-log.txt`, matching the earlier parts' style. Plain text,
lines of at most 80 columns, CRLF line endings. Cover:
- why (the review findings)
- the Cookbook direction and tokens
- how dark mode works (stored choice, the first-paint script)
- the phone header
- photo srcset
- what the user should check on their phone

Check with
`python -c "d=open('docs/plateful-frontend-dev-log.txt','rb').read(); print(max(len(l) for l in d.split(b'\r\n')), d.count(b'\r\n'), d.count(b'\n'))"`:
the longest line is at most 80, and both counts are equal.

- [ ] **Step 5: Run everything and commit**

Run: `npx vitest run` then `npm run build`, both green.
```bash
git add src/index.css docs/plateful-frontend-dev-log.txt <any files fixed in steps 1 and 3>
git commit -m "chore(frontend): drop Golden Hour tokens; dev log PART 21"
```
