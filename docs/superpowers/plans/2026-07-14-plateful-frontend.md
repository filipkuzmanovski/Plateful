# Plateful Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build "Plateful" — a React SPA in `recipe-app-frontend/` covering browse/filter, recipe detail with ratings/comments/reactions, Supabase auth with profile provisioning, recipe authoring, and profiles, in the Fresh Editorial visual style.

**Architecture:** Vite + React 19 + TypeScript SPA. React Router (v7) pages, TanStack Query for all server state, supabase-js for auth ONLY (all data through our Spring API), typed fetch client that speaks ProblemDetail and `Page<T>`, Tailwind CSS v4 theme tokens. Feature-folder layout per the spec.

**Tech Stack:** Vite 7, React 19, TypeScript, react-router v7, @tanstack/react-query v5, @supabase/supabase-js v2, Tailwind CSS v4 (@tailwindcss/vite), react-hook-form + zod, @radix-ui/react-dialog + sonner (toasts), Vitest + React Testing Library + MSW v2.

**Spec:** `docs/superpowers/specs/2026-07-14-frontend-design.md`

## Global Constraints

- App name/wordmark: **PLATEFUL** (extrabold, tight tracking). Design tokens: ink `#111111`, accent green `#16a34a`, hairline `#e5e5e5`, muted `#737373`, white background, square corners, Inter (self-hosted via Fontsource).
- supabase-js is used ONLY for auth; every data call goes through our API client. Env vars: `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`, `VITE_API_BASE_URL` (default `http://localhost:8080`).
- API shapes (fixed by the backend): ProblemDetail errors `{status, detail, errors?}`; pages `{content: T[], page: {size, number, totalElements, totalPages}}`; browse filters `cuisine, dairyFree, glutenFree, vegan, vegetarian, maxReadyInMinutes, minRating`; page size 12 requested.
- Provisioning: after login `GET /api/users/me`; 404 → `/complete-profile` → `POST /api/users/me {firstName, lastName}` (idempotent). 401 from the API → supabase signOut (client layer), routing then redirects.
- Working directory for ALL commands: `C:\Users\Gaming-PC\Desktop\RecipeApplication\recipe-app-frontend` (except Task 1's scaffold command, run from the repo root). **Run npm/npx via the Bash (POSIX) tool** — PowerShell mangles `--` argument separators.
- Test command: `npx vitest run` (one-shot). Type/build check: `npm run build`.
- **Spec-sanctioned realization note:** the spec's "shadcn/ui primitives" are implemented as hand-written Tailwind components over the same underlying tech (Radix primitive for the dialog, native form controls, sonner for toasts) — no shadcn CLI, because its interactive prompts are unreliable in non-TTY automation. Same accessibility and look; fewer moving parts.
- Commit after every task; subjects exactly as given; no co-author lines.

---

### Task 1: Scaffold, theme, test infrastructure

**Files:**
- Create (via CLI): `recipe-app-frontend/` Vite react-ts app
- Modify: `recipe-app-frontend/vite.config.ts`, `tsconfig.app.json`, `src/index.css`, `index.html`
- Create: `src/components/ui.tsx`, `src/test/setup.ts`, `src/test/server.ts`, `.env.example`
- Delete: `src/App.css`, `src/assets/react.svg`, `public/vite.svg`
- Test: `src/App.test.tsx` (temporary smoke test; replaced in Task 3)

**Interfaces:**
- Consumes: nothing.
- Produces: running Vite+Vitest toolchain with `@/` alias; Tailwind tokens `text-ink`, `text-accent`, `text-muted`, `border-hairline`, `bg-accent`; shared UI atoms `Button`, `Field`, `SectionHeading`, `Spinner` from `@/components/ui`; MSW `server` from `@/test/server`.

- [ ] **Step 1: Scaffold and install dependencies** (Bash, from `C:/Users/Gaming-PC/Desktop/RecipeApplication`)

```bash
npm create vite@latest recipe-app-frontend -- --template react-ts
cd recipe-app-frontend
npm install
npm install react-router @tanstack/react-query @supabase/supabase-js react-hook-form zod @hookform/resolvers @radix-ui/react-dialog sonner @fontsource-variable/inter tailwindcss @tailwindcss/vite
npm install -D vitest jsdom @testing-library/react @testing-library/user-event @testing-library/jest-dom msw @types/node
rm src/App.css src/assets/react.svg public/vite.svg
```

- [ ] **Step 2: Configure Vite + Vitest + alias**

Replace `vite.config.ts`:

```ts
/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'node:path';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': path.resolve(__dirname, 'src') } },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    css: false,
  },
});
```

In `tsconfig.app.json`, add to `compilerOptions`:

```json
    "baseUrl": ".",
    "paths": { "@/*": ["./src/*"] },
    "types": ["vitest/globals", "@testing-library/jest-dom"]
```

- [ ] **Step 3: Theme (replace `src/index.css`)**

```css
@import 'tailwindcss';
@import '@fontsource-variable/inter';

@theme {
  --font-sans: 'Inter Variable', system-ui, sans-serif;
  --color-ink: #111111;
  --color-accent: #16a34a;
  --color-hairline: #e5e5e5;
  --color-muted: #737373;
}

body {
  @apply bg-white font-sans text-ink antialiased;
}
```

In `index.html` set `<title>Plateful</title>` and `<html lang="en">`.

- [ ] **Step 4: Shared UI atoms — create `src/components/ui.tsx`**

```tsx
import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react';

export function Button({
  variant = 'primary',
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'outline' | 'danger' }) {
  const styles = {
    primary: 'bg-ink text-white hover:bg-accent',
    outline: 'border border-ink text-ink hover:bg-ink hover:text-white',
    danger: 'border border-red-600 text-red-600 hover:bg-red-600 hover:text-white',
  }[variant];
  return (
    <button
      className={`px-4 py-2 text-sm font-bold tracking-wide uppercase transition-colors disabled:opacity-40 ${styles} ${className}`}
      {...props}
    />
  );
}

export function Field({
  label,
  error,
  children,
}: {
  label: string;
  error?: string;
  children: ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-bold tracking-widest text-ink uppercase">{label}</span>
      {children}
      {error && <span role="alert" className="mt-1 block text-xs text-red-600">{error}</span>}
    </label>
  );
}

export function TextInput(props: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className="w-full border border-hairline px-3 py-2 text-sm focus:border-ink focus:outline-none"
      {...props}
    />
  );
}

export function SectionHeading({ children }: { children: ReactNode }) {
  return (
    <h2 className="mt-8 mb-3 border-b-2 border-ink pb-1 text-sm font-bold tracking-widest uppercase">
      {children}
    </h2>
  );
}

export function Spinner() {
  return <p role="status" className="py-12 text-center text-sm text-muted">Loading…</p>;
}
```

- [ ] **Step 5: Test infrastructure**

Create `src/test/server.ts`:

```ts
import { setupServer } from 'msw/node';

// Individual tests register handlers with server.use(...).
export const server = setupServer();
```

Create `src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './server';

beforeAll(() => server.listen({ onUnhandledRequest: 'warn' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
```

Create `.env.example`:

```
VITE_SUPABASE_URL=https://YOUR-PROJECT.supabase.co
VITE_SUPABASE_ANON_KEY=YOUR-ANON-KEY
VITE_API_BASE_URL=http://localhost:8080
```

(Vite's template `.gitignore` already ignores `*.local`, which covers the real `.env.local`.)

- [ ] **Step 6: Smoke test + minimal App**

Replace `src/App.tsx`:

```tsx
export default function App() {
  return <h1 className="text-2xl font-extrabold tracking-tight">PLATEFUL</h1>;
}
```

Replace `src/main.tsx`:

```tsx
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import App from './App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
```

Create `src/App.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import App from './App';

test('renders the wordmark', () => {
  render(<App />);
  expect(screen.getByText('PLATEFUL')).toBeInTheDocument();
});
```

- [ ] **Step 7: Verify**

Run: `npx vitest run` — Expected: `1 passed`.
Run: `npm run build` — Expected: build succeeds (tsc + vite).

- [ ] **Step 8: Commit**

```bash
cd .. && git add recipe-app-frontend && git commit -m "feat: scaffold Plateful frontend (Vite, Tailwind theme, test infra)"
```

---

### Task 2: Types, API client, resource modules

**Files:**
- Create: `src/lib/types.ts`, `src/api/client.ts`, `src/api/recipes.ts`, `src/api/users.ts`, `src/api/ratings.ts`, `src/api/comments.ts`, `src/api/reactions.ts`, `src/auth/supabaseClient.ts`, `src/test/fixtures.ts`
- Test: `src/api/client.test.ts`

**Interfaces:**
- Consumes: `server` from Task 1.
- Produces (used by every later task):
  - Types: `UserDisplay {id, firstName, lastName}`, `IngredientDisplay {ingredientName, originalText}`, `InstructionStep {number, step, ingredients: string[], equipment: string[]}`, `RecipeDisplay` (all 28 backend DTO fields, numerics/strings nullable), `CommentDisplay {id, user, body, likeCount, dislikeCount, createdAt}`, `RatingDisplay {id, user, rating, createdAt}`, `ReactionDisplay {user, isLike, createdAt}`, `Page<T> {content: T[], page: {size, number, totalElements, totalPages}}`, `RecipeFilter` (all optional), `RecipeCreate` (mirrors backend create DTO), `ApiError`.
  - `api<T>(path, init?)` — fetch wrapper; `apiVoid(path, init?)` for 204s.
  - Resource functions: `browseRecipes(filter, page)`, `getRecipe(id)`, `createRecipe(dto)`, `updateRecipe(id, dto)`, `deleteRecipe(id)`; `getMe()`, `createMe(names)`, `updateMe(names)`, `getUser(id)`, `getUserRecipes(id, page)`; `getMyRating(recipeId)` (404 → null), `putMyRating(recipeId, rating)`, `deleteMyRating(recipeId)`; `getComments(recipeId, page)`, `addComment(recipeId, body)`, `editComment(id, body)`, `deleteComment(id)`; `putMyReaction(commentId, isLike)`, `deleteMyReaction(commentId)`.
  - Fixtures: `recipeFixture(overrides?)`, `pageOf(content, pageOverrides?)`, `profileFixture`, `commentFixture(overrides?)`.

- [ ] **Step 1: Write the failing test — `src/api/client.test.ts`**

```ts
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { api, ApiError } from '@/api/client';
import { browseRecipes } from '@/api/recipes';
import { getMyRating } from '@/api/ratings';
import { recipeFixture, pageOf } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

test('parses JSON and sends no auth header when anonymous', async () => {
  let authHeader: string | null = 'unset';
  server.use(
    http.get(`${BASE}/api/ping`, ({ request }) => {
      authHeader = request.headers.get('Authorization');
      return HttpResponse.json({ ok: true });
    }),
  );
  const result = await api<{ ok: boolean }>('/api/ping');
  expect(result.ok).toBe(true);
  expect(authHeader).toBeNull();
});

test('throws typed ApiError from a ProblemDetail body', async () => {
  server.use(
    http.post(`${BASE}/api/ping`, () =>
      HttpResponse.json(
        { status: 400, detail: 'Validation failed', errors: { title: 'must not be blank' } },
        { status: 400 },
      ),
    ),
  );
  const err = await api('/api/ping', { method: 'POST', body: JSON.stringify({}) }).catch((e) => e);
  expect(err).toBeInstanceOf(ApiError);
  expect(err.status).toBe(400);
  expect(err.detail).toBe('Validation failed');
  expect(err.errors).toEqual({ title: 'must not be blank' });
});

test('browseRecipes serializes only the set filters and pagination', async () => {
  let url = '';
  server.use(
    http.get(`${BASE}/api/recipes`, ({ request }) => {
      url = request.url;
      return HttpResponse.json(pageOf([recipeFixture()]));
    }),
  );
  const result = await browseRecipes({ cuisine: 'italian', vegan: true }, 2);
  const params = new URL(url).searchParams;
  expect(params.get('cuisine')).toBe('italian');
  expect(params.get('vegan')).toBe('true');
  expect(params.get('dairyFree')).toBeNull(); // unset filter omitted entirely
  expect(params.get('page')).toBe('2');
  expect(params.get('size')).toBe('12');
  expect(result.content[0].title).toBe('Bruschetta Pork & Pasta');
});

test('getMyRating maps 404 to null', async () => {
  server.use(
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'no rating' }, { status: 404 }),
    ),
  );
  expect(await getMyRating('r-1')).toBeNull();
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/api/client.test.ts` — Expected: FAIL (modules don't exist).

- [ ] **Step 3: Implement**

Create `src/lib/types.ts`:

```ts
export interface UserDisplay { id: string; firstName: string; lastName: string; }
export interface IngredientDisplay { ingredientName: string; originalText: string; }
export interface InstructionStep { number: number; step: string; ingredients: string[]; equipment: string[]; }

export interface RecipeDisplay {
  id: string; source: string; user: UserDisplay | null; title: string;
  image: string | null; servings: number | null; readyInMinutes: number | null;
  cookingMinutes: number | null; preparationMinutes: number | null;
  sourceName: string | null; sourceUrl: string | null; instructions: string | null;
  instructionsSteps: InstructionStep[]; dairyFree: boolean; glutenFree: boolean;
  vegan: boolean; vegetarian: boolean; cuisines: string[];
  calories: number | null; proteinGrams: number | null; fatGrams: number | null;
  carbsGrams: number | null; nutrition: Record<string, unknown> | null;
  ingredients: IngredientDisplay[]; averageRating: number | null;
  ratingCount: number; commentCount: number; createdAt: string | null;
}

export interface CommentDisplay { id: string; user: UserDisplay; body: string; likeCount: number; dislikeCount: number; createdAt: string | null; }
export interface RatingDisplay { id: string; user: UserDisplay; rating: number; createdAt: string | null; }
export interface ReactionDisplay { user: UserDisplay; isLike: boolean; createdAt: string | null; }

export interface Page<T> { content: T[]; page: { size: number; number: number; totalElements: number; totalPages: number }; }

export interface RecipeFilter {
  cuisine?: string; dairyFree?: boolean; glutenFree?: boolean; vegan?: boolean;
  vegetarian?: boolean; maxReadyInMinutes?: number; minRating?: number;
}

export interface IngredientCreate { ingredientName: string; originalText: string; }
export interface RecipeCreate {
  title: string; image: string | null; servings: number | null; readyInMinutes: number | null;
  cookingMinutes: number | null; preparationMinutes: number | null;
  sourceName: string | null; sourceUrl: string | null; instructions: string | null;
  instructionsSteps: InstructionStep[]; dairyFree: boolean; glutenFree: boolean;
  vegan: boolean; vegetarian: boolean; cuisines: string[]; ingredients: IngredientCreate[];
}
```

Create `src/auth/supabaseClient.ts`:

```ts
import { createClient } from '@supabase/supabase-js';

// Auth ONLY. All data goes through our API (src/api) — never Supabase tables.
export const supabase = createClient(
  import.meta.env.VITE_SUPABASE_URL ?? 'http://localhost:54321',
  import.meta.env.VITE_SUPABASE_ANON_KEY ?? 'test-anon-key',
);
```

Create `src/api/client.ts`:

```ts
import { supabase } from '@/auth/supabaseClient';

const BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export class ApiError extends Error {
  constructor(
    public status: number,
    public detail: string,
    public errors?: Record<string, string>,
  ) {
    super(detail);
  }
}

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set('Content-Type', 'application/json');
  const { data } = await supabase.auth.getSession();
  const token = data.session?.access_token;
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const res = await fetch(`${BASE}${path}`, { ...init, headers });
  if (res.ok) return res;

  if (res.status === 401) await supabase.auth.signOut(); // expired/invalid token — routing reacts to session loss
  const problem = await res.json().catch(() => null);
  throw new ApiError(res.status, problem?.detail ?? `Request failed (${res.status})`, problem?.errors);
}

/** JSON-returning call. */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await request(path, init);
  return res.json() as Promise<T>;
}

/** 204-returning call. */
export async function apiVoid(path: string, init: RequestInit = {}): Promise<void> {
  await request(path, init);
}
```

Create `src/api/recipes.ts`:

```ts
import { api, apiVoid } from './client';
import type { Page, RecipeCreate, RecipeDisplay, RecipeFilter } from '@/lib/types';

export const PAGE_SIZE = 12;

export function browseRecipes(filter: RecipeFilter, page: number): Promise<Page<RecipeDisplay>> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filter)) {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value));
  }
  params.set('page', String(page));
  params.set('size', String(PAGE_SIZE));
  return api(`/api/recipes?${params}`);
}

export const getRecipe = (id: string) => api<RecipeDisplay>(`/api/recipes/${id}`);
export const createRecipe = (dto: RecipeCreate) =>
  api<RecipeDisplay>('/api/recipes', { method: 'POST', body: JSON.stringify(dto) });
export const updateRecipe = (id: string, dto: RecipeCreate) =>
  api<RecipeDisplay>(`/api/recipes/${id}`, { method: 'PUT', body: JSON.stringify(dto) });
export const deleteRecipe = (id: string) => apiVoid(`/api/recipes/${id}`, { method: 'DELETE' });
```

Create `src/api/users.ts`:

```ts
import { api } from './client';
import type { Page, RecipeDisplay, UserDisplay } from '@/lib/types';
import { PAGE_SIZE } from './recipes';

export interface Names { firstName: string; lastName: string; }

export const getMe = () => api<UserDisplay>('/api/users/me');
export const createMe = (names: Names) =>
  api<UserDisplay>('/api/users/me', { method: 'POST', body: JSON.stringify(names) });
export const updateMe = (names: Names) =>
  api<UserDisplay>('/api/users/me', { method: 'PUT', body: JSON.stringify(names) });
export const getUser = (id: string) => api<UserDisplay>(`/api/users/${id}`);
export const getUserRecipes = (id: string, page: number) =>
  api<Page<RecipeDisplay>>(`/api/users/${id}/recipes?page=${page}&size=${PAGE_SIZE}`);
```

Create `src/api/ratings.ts`:

```ts
import { api, apiVoid, ApiError } from './client';

export async function getMyRating(recipeId: string): Promise<number | null> {
  try {
    const result = await api<{ rating: number }>(`/api/recipes/${recipeId}/ratings/me`);
    return result.rating;
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

export const putMyRating = (recipeId: string, rating: number) =>
  apiVoid(`/api/recipes/${recipeId}/ratings/me`, { method: 'PUT', body: JSON.stringify({ rating }) });
export const deleteMyRating = (recipeId: string) =>
  apiVoid(`/api/recipes/${recipeId}/ratings/me`, { method: 'DELETE' });
```

Create `src/api/comments.ts`:

```ts
import { api, apiVoid } from './client';
import type { CommentDisplay, Page } from '@/lib/types';

export const getComments = (recipeId: string, page: number) =>
  api<Page<CommentDisplay>>(`/api/recipes/${recipeId}/comments?page=${page}&size=10`);
export const addComment = (recipeId: string, body: string) =>
  api<CommentDisplay>(`/api/recipes/${recipeId}/comments`, { method: 'POST', body: JSON.stringify({ body }) });
export const editComment = (id: string, body: string) =>
  api<CommentDisplay>(`/api/comments/${id}`, { method: 'PUT', body: JSON.stringify({ body }) });
export const deleteComment = (id: string) => apiVoid(`/api/comments/${id}`, { method: 'DELETE' });
```

Create `src/api/reactions.ts`:

```ts
import { apiVoid } from './client';

export const putMyReaction = (commentId: string, isLike: boolean) =>
  apiVoid(`/api/comments/${commentId}/reactions/me`, { method: 'PUT', body: JSON.stringify({ isLike }) });
export const deleteMyReaction = (commentId: string) =>
  apiVoid(`/api/comments/${commentId}/reactions/me`, { method: 'DELETE' });
```

Create `src/test/fixtures.ts`:

```ts
import type { CommentDisplay, Page, RecipeDisplay, UserDisplay } from '@/lib/types';

export const profileFixture: UserDisplay = { id: 'u-1', firstName: 'Jane', lastName: 'Doe' };

export function recipeFixture(overrides: Partial<RecipeDisplay> = {}): RecipeDisplay {
  return {
    id: 'r-1', source: 'spoonacular', user: null, title: 'Bruschetta Pork & Pasta',
    image: null, servings: 5, readyInMinutes: 35, cookingMinutes: 25, preparationMinutes: 10,
    sourceName: 'Pink When', sourceUrl: 'https://example.com/r', instructions: 'Cook the pasta. Serve.',
    instructionsSteps: [
      { number: 1, step: 'Boil the pasta.', ingredients: ['pasta'], equipment: ['pot'] },
      { number: 2, step: 'Sear the pork.', ingredients: ['pork chops'], equipment: [] },
    ],
    dairyFree: true, glutenFree: true, vegan: false, vegetarian: false, cuisines: ['italian'],
    calories: 543.36, proteinGrams: 21.1, fatGrams: 16.2, carbsGrams: 74.79, nutrition: null,
    ingredients: [
      { ingredientName: 'penne', originalText: '8 oz penne pasta' },
      { ingredientName: 'pork chops', originalText: '2 boneless pork chops' },
    ],
    averageRating: 4.6, ratingCount: 28, commentCount: 2, createdAt: '2026-07-14T00:00:00Z',
    ...overrides,
  };
}

export function commentFixture(overrides: Partial<CommentDisplay> = {}): CommentDisplay {
  return {
    id: 'c-1', user: profileFixture, body: 'Delicious!', likeCount: 3, dislikeCount: 0,
    createdAt: '2026-07-14T00:00:00Z', ...overrides,
  };
}

export function pageOf<T>(content: T[], pageOverrides = {}): Page<T> {
  return {
    content,
    page: { size: 12, number: 0, totalElements: content.length, totalPages: 1, ...pageOverrides },
  };
}
```

- [ ] **Step 4: Run to verify green**

Run: `npx vitest run` — Expected: `5 passed` (4 new + Task 1 smoke).

- [ ] **Step 5: Commit**

```bash
cd .. && git add recipe-app-frontend/src && git commit -m "feat: typed API client, resource modules, test fixtures"
```

---

### Task 3: Auth, routing shell, provisioning

**Files:**
- Create: `src/auth/AuthProvider.tsx`, `src/auth/ProtectedRoute.tsx`, `src/features/auth/LoginPage.tsx`, `src/features/auth/SignupPage.tsx`, `src/features/profile/CompleteProfilePage.tsx`, `src/components/AppShell.tsx`, `src/components/ErrorBoundary.tsx`, `src/AppRoutes.tsx`, `src/test/utils.tsx`
- Modify: `src/App.tsx`, `src/main.tsx`
- Delete: `src/App.test.tsx` (superseded)
- Test: `src/auth/auth.test.tsx`

**Interfaces:**
- Consumes: `getMe`, `createMe`, `ApiError` (Task 2), `supabase`, UI atoms.
- Produces:
  - `useAuth()` → `{ session: Session | null, profile: UserDisplay | null, status: 'loading' | 'anonymous' | 'needs-profile' | 'ready', signOut(): Promise<void>, setProfile(p: UserDisplay): void }`.
  - `<ProtectedRoute>` wrapper (redirects anonymous → `/login`, needs-profile → `/complete-profile`).
  - `<AppRoutes />` — the full route table (later tasks REPLACE the named placeholder page components; routes and paths are final here).
  - `renderApp(initialRoute?)` test helper; authed tests mock `@/auth/supabaseClient` with the documented `vi.hoisted` pattern.

- [ ] **Step 1: Write the failing test — `src/auth/auth.test.tsx`**

```tsx
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { profileFixture } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

// Authed-session mock: replaces the real supabase client for this whole file.
vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({
        data: { session: { access_token: 'test-token', user: { id: 'u-1', email: 'jane@example.com' } } },
      }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
    },
  },
}));

test('logged-in user without a profile is redirected to complete-profile, and completing it proceeds', async () => {
  let posted: unknown = null;
  server.use(
    http.get(`${BASE}/api/users/me`, () =>
      HttpResponse.json({ status: 404, detail: 'User not found' }, { status: 404 }),
    ),
    http.post(`${BASE}/api/users/me`, async ({ request }) => {
      posted = await request.json();
      return HttpResponse.json(profileFixture);
    }),
    http.get(`${BASE}/api/recipes`, () =>
      HttpResponse.json({ content: [], page: { size: 12, number: 0, totalElements: 0, totalPages: 0 } }),
    ),
  );

  renderApp('/me'); // protected route

  // provisioning redirect
  expect(await screen.findByRole('heading', { name: /complete your profile/i })).toBeInTheDocument();

  await userEvent.type(screen.getByLabelText(/first name/i), 'Jane');
  await userEvent.type(screen.getByLabelText(/last name/i), 'Doe');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));

  expect(await screen.findByText(/jane doe/i)).toBeInTheDocument(); // landed on /me
  expect(posted).toEqual({ firstName: 'Jane', lastName: 'Doe' });
});

test('logged-in user with a profile sees their name in the header', async () => {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/recipes`, () =>
      HttpResponse.json({ content: [], page: { size: 12, number: 0, totalElements: 0, totalPages: 0 } }),
    ),
  );
  renderApp('/');
  expect(await screen.findByRole('button', { name: /jane/i })).toBeInTheDocument();
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/auth/auth.test.tsx` — Expected: FAIL (missing modules).

- [ ] **Step 3: Implement**

Create `src/auth/AuthProvider.tsx`:

```tsx
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import type { Session } from '@supabase/supabase-js';
import { supabase } from './supabaseClient';
import { getMe } from '@/api/users';
import { ApiError } from '@/api/client';
import type { UserDisplay } from '@/lib/types';

type Status = 'loading' | 'anonymous' | 'needs-profile' | 'ready';

interface AuthState {
  session: Session | null;
  profile: UserDisplay | null;
  status: Status;
  signOut: () => Promise<void>;
  setProfile: (p: UserDisplay) => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [profile, setProfileState] = useState<UserDisplay | null>(null);
  const [status, setStatus] = useState<Status>('loading');

  useEffect(() => {
    let cancelled = false;

    async function resolveProfile(s: Session | null) {
      if (!s) {
        if (!cancelled) { setProfileState(null); setStatus('anonymous'); }
        return;
      }
      try {
        const me = await getMe();
        if (!cancelled) { setProfileState(me); setStatus('ready'); }
      } catch (e) {
        if (!cancelled && e instanceof ApiError && e.status === 404) {
          setProfileState(null);
          setStatus('needs-profile');
        } else if (!cancelled) {
          setProfileState(null);
          setStatus('anonymous'); // e.g. 401 already signed us out
        }
      }
    }

    supabase.auth.getSession().then(({ data }) => {
      if (cancelled) return;
      setSession(data.session);
      void resolveProfile(data.session);
    });
    const { data: { subscription } } = supabase.auth.onAuthStateChange((_event, s) => {
      setSession(s);
      setStatus('loading');
      void resolveProfile(s);
    });
    return () => { cancelled = true; subscription.unsubscribe(); };
  }, []);

  const value: AuthState = {
    session,
    profile,
    status,
    signOut: async () => { await supabase.auth.signOut(); },
    setProfile: (p) => { setProfileState(p); setStatus('ready'); },
  };
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
```

Create `src/auth/ProtectedRoute.tsx`:

```tsx
import { Navigate, useLocation } from 'react-router';
import type { ReactNode } from 'react';
import { useAuth } from './AuthProvider';
import { Spinner } from '@/components/ui';

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'loading') return <Spinner />;
  if (status === 'anonymous') return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  if (status === 'needs-profile' && location.pathname !== '/complete-profile')
    return <Navigate to="/complete-profile" replace />;
  return <>{children}</>;
}
```

Create `src/features/auth/LoginPage.tsx`:

```tsx
import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { supabase } from '@/auth/supabaseClient';
import { Button, Field, TextInput } from '@/components/ui';

export default function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const { error } = await supabase.auth.signInWithPassword({ email, password });
    setBusy(false);
    if (error) setError(error.message);
    else navigate((location.state as { from?: string } | null)?.from ?? '/');
  }

  return (
    <div className="mx-auto max-w-sm py-16">
      <h1 className="mb-6 text-2xl font-extrabold tracking-tight">Sign in</h1>
      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="Email">
          <TextInput type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </Field>
        <Field label="Password">
          <TextInput type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </Field>
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        <Button type="submit" disabled={busy} className="w-full">Sign in</Button>
      </form>
      <p className="mt-4 text-sm text-muted">
        No account? <Link to="/signup" className="text-accent underline">Sign up</Link>
      </p>
    </div>
  );
}
```

Create `src/features/auth/SignupPage.tsx`:

```tsx
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { supabase } from '@/auth/supabaseClient';
import { Button, Field, TextInput } from '@/components/ui';

export default function SignupPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const { error } = await supabase.auth.signUp({ email, password });
    setBusy(false);
    if (error) setError(error.message);
    else navigate('/complete-profile'); // provisioning happens there
  }

  return (
    <div className="mx-auto max-w-sm py-16">
      <h1 className="mb-6 text-2xl font-extrabold tracking-tight">Create account</h1>
      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="Email">
          <TextInput type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </Field>
        <Field label="Password">
          <TextInput type="password" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={6} />
        </Field>
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        <Button type="submit" disabled={busy} className="w-full">Sign up</Button>
      </form>
      <p className="mt-4 text-sm text-muted">
        Have an account? <Link to="/login" className="text-accent underline">Sign in</Link>
      </p>
    </div>
  );
}
```

Create `src/features/profile/CompleteProfilePage.tsx`:

```tsx
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { createMe } from '@/api/users';
import { useAuth } from '@/auth/AuthProvider';
import { ApiError } from '@/api/client';
import { Button, Field, TextInput } from '@/components/ui';

export default function CompleteProfilePage() {
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const { setProfile } = useAuth();
  const navigate = useNavigate();

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const profile = await createMe({ firstName, lastName });
      setProfile(profile);
      navigate('/me');
    } catch (err) {
      setError(err instanceof ApiError ? err.detail : 'Something went wrong');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-sm py-16">
      <h1 className="mb-2 text-2xl font-extrabold tracking-tight">Complete your profile</h1>
      <p className="mb-6 text-sm text-muted">Tell us what to call you — shown next to your recipes and comments.</p>
      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="First name">
          <TextInput value={firstName} onChange={(e) => setFirstName(e.target.value)} required maxLength={255} />
        </Field>
        <Field label="Last name">
          <TextInput value={lastName} onChange={(e) => setLastName(e.target.value)} required maxLength={255} />
        </Field>
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        <Button type="submit" disabled={busy} className="w-full">Save</Button>
      </form>
    </div>
  );
}
```

Create `src/components/ErrorBoundary.tsx` (route-level render-crash net, per spec):

```tsx
import { Component, type ReactNode } from 'react';

export class ErrorBoundary extends Component<{ children: ReactNode }, { hasError: boolean }> {
  state = { hasError: false };

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  render() {
    if (this.state.hasError) {
      return <p className="py-12 text-center text-muted">Something went wrong rendering this page.</p>;
    }
    return this.props.children;
  }
}
```

Create `src/components/AppShell.tsx`:

```tsx
import { Link, Outlet, useNavigate } from 'react-router';
import { useState } from 'react';
import { Toaster } from 'sonner';
import { useAuth } from '@/auth/AuthProvider';
import { ErrorBoundary } from './ErrorBoundary';

export default function AppShell() {
  const { status, profile, signOut } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const navigate = useNavigate();

  return (
    <div className="mx-auto max-w-6xl px-4">
      <header className="flex items-center justify-between border-b-2 border-ink py-4">
        <Link to="/" className="text-2xl font-extrabold tracking-tighter">PLATEFUL</Link>
        <nav className="flex items-center gap-5 text-sm">
          <Link to="/" className="hover:text-accent">Browse</Link>
          {status === 'ready' && (
            <Link to="/recipes/new" className="hover:text-accent">New Recipe</Link>
          )}
          {status === 'ready' && profile ? (
            <div className="relative">
              <button
                onClick={() => setMenuOpen((v) => !v)}
                className="font-bold uppercase tracking-wide hover:text-accent"
              >
                {profile.firstName}
              </button>
              {menuOpen && (
                <div className="absolute right-0 z-10 mt-2 w-40 border border-ink bg-white text-left">
                  <Link to="/me" onClick={() => setMenuOpen(false)} className="block px-3 py-2 hover:bg-ink hover:text-white">
                    My profile
                  </Link>
                  <button
                    onClick={async () => { setMenuOpen(false); await signOut(); navigate('/'); }}
                    className="block w-full px-3 py-2 text-left hover:bg-ink hover:text-white"
                  >
                    Sign out
                  </button>
                </div>
              )}
            </div>
          ) : status !== 'loading' ? (
            <Link to="/login" className="border-2 border-ink px-3 py-1 font-bold uppercase tracking-wide hover:bg-ink hover:text-white">
              Sign in
            </Link>
          ) : null}
        </nav>
      </header>
      <main className="py-8">
        <ErrorBoundary>
          <Outlet />
        </ErrorBoundary>
      </main>
      <Toaster position="bottom-right" />
    </div>
  );
}
```

Create `src/AppRoutes.tsx` (placeholder pages get REPLACED by Tasks 4–8; routes are final):

```tsx
import { Route, Routes } from 'react-router';
import AppShell from '@/components/AppShell';
import { ProtectedRoute } from '@/auth/ProtectedRoute';
import LoginPage from '@/features/auth/LoginPage';
import SignupPage from '@/features/auth/SignupPage';
import CompleteProfilePage from '@/features/profile/CompleteProfilePage';
import BrowsePage from '@/features/browse/BrowsePage';
import RecipeDetailPage from '@/features/recipe/RecipeDetailPage';
import RecipeFormPage from '@/features/authoring/RecipeFormPage';
import MyProfilePage from '@/features/profile/MyProfilePage';
import PublicProfilePage from '@/features/profile/PublicProfilePage';

export default function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<BrowsePage />} />
        <Route path="/recipes/new" element={<ProtectedRoute><RecipeFormPage /></ProtectedRoute>} />
        <Route path="/recipes/:id" element={<RecipeDetailPage />} />
        <Route path="/recipes/:id/edit" element={<ProtectedRoute><RecipeFormPage /></ProtectedRoute>} />
        <Route path="/users/:id" element={<PublicProfilePage />} />
        <Route path="/me" element={<ProtectedRoute><MyProfilePage /></ProtectedRoute>} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/complete-profile" element={<ProtectedRoute><CompleteProfilePage /></ProtectedRoute>} />
        <Route path="*" element={<p className="py-12 text-center text-muted">Page not found.</p>} />
      </Route>
    </Routes>
  );
}
```

Create PLACEHOLDER pages so the app compiles (each replaced by its own later task):

`src/features/browse/BrowsePage.tsx`:

```tsx
export default function BrowsePage() {
  return <p className="text-muted">Browse coming in Task 4.</p>;
}
```

`src/features/recipe/RecipeDetailPage.tsx`:

```tsx
export default function RecipeDetailPage() {
  return <p className="text-muted">Detail coming in Task 5.</p>;
}
```

`src/features/authoring/RecipeFormPage.tsx`:

```tsx
export default function RecipeFormPage() {
  return <p className="text-muted">Authoring coming in Task 7.</p>;
}
```

`src/features/profile/PublicProfilePage.tsx`:

```tsx
export default function PublicProfilePage() {
  return <p className="text-muted">Profile coming in Task 8.</p>;
}
```

`src/features/profile/MyProfilePage.tsx`:

```tsx
import { useAuth } from '@/auth/AuthProvider';

export default function MyProfilePage() {
  const { profile } = useAuth();
  return <h1 className="text-2xl font-extrabold">{profile ? `${profile.firstName} ${profile.lastName}` : ''}</h1>;
}
```

(MyProfilePage renders the real name already so the provisioning test can assert the landing; Task 8 replaces it with the full page.)

Replace `src/App.tsx`:

```tsx
import { BrowserRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from '@/auth/AuthProvider';
import AppRoutes from '@/AppRoutes';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 2, staleTime: 30_000 } },
});

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <AppRoutes />
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}
```

(`src/main.tsx` from Task 1 already renders `<App />` — unchanged.)

Create `src/test/utils.tsx`:

```tsx
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from '@/auth/AuthProvider';
import AppRoutes from '@/AppRoutes';

export function renderApp(initialRoute = '/') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[initialRoute]}>
          <AppRoutes />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}
```

Delete `src/App.test.tsx`.

- [ ] **Step 4: Run to verify green**

Run: `npx vitest run` — Expected: `6 passed` (4 from Task 2 + 2 new).
Run: `npm run build` — Expected: success.

- [ ] **Step 5: Commit**

```bash
cd .. && git add -A recipe-app-frontend/src && git commit -m "feat: auth provider, provisioning flow, routing shell"
```

---

### Task 4: Browse page

**Files:**
- Create: `src/components/RecipeCard.tsx`, `src/components/StarDisplay.tsx`, `src/components/Pagination.tsx`, `src/features/browse/FilterBar.tsx`
- Replace: `src/features/browse/BrowsePage.tsx`
- Test: `src/features/browse/browse.test.tsx`

**Interfaces:**
- Consumes: `browseRecipes`, fixtures, `renderApp`.
- Produces: `RecipeCard({recipe})`, `StarDisplay({average, count})`, `Pagination({page, totalPages, onPage})` — reused by Tasks 5 and 8. `CUISINES` constant exported from `FilterBar.tsx`.

- [ ] **Step 1: Write the failing test — `src/features/browse/browse.test.tsx`**

```tsx
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, pageOf } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

function captureRecipesUrl(store: { url: string }, body = pageOf([recipeFixture()])) {
  server.use(
    http.get(`${BASE}/api/recipes`, ({ request }) => {
      store.url = request.url;
      return HttpResponse.json(body);
    }),
  );
}

test('renders recipe cards from the API', async () => {
  captureRecipesUrl({ url: '' });
  renderApp('/');
  expect(await screen.findByText('Bruschetta Pork & Pasta')).toBeInTheDocument();
  expect(screen.getByText(/4\.6/)).toBeInTheDocument();
  expect(screen.getByText(/35 min/i)).toBeInTheDocument();
});

test('setting a filter updates the request params', async () => {
  const store = { url: '' };
  captureRecipesUrl(store);
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');

  await userEvent.selectOptions(screen.getByLabelText(/cuisine/i), 'italian');
  await userEvent.click(screen.getByRole('button', { name: /vegan/i }));

  await vi.waitFor(() => {
    const params = new URL(store.url).searchParams;
    expect(params.get('cuisine')).toBe('italian');
    expect(params.get('vegan')).toBe('true');
    expect(params.get('page')).toBe('0'); // filter change resets page
  });
});

test('filters initialize from the URL', async () => {
  const store = { url: '' };
  captureRecipesUrl(store);
  renderApp('/?cuisine=thai&maxReadyInMinutes=30&page=1');
  await screen.findByText('Bruschetta Pork & Pasta');
  const params = new URL(store.url).searchParams;
  expect(params.get('cuisine')).toBe('thai');
  expect(params.get('maxReadyInMinutes')).toBe('30');
  expect(params.get('page')).toBe('1');
});

test('empty result shows the empty state', async () => {
  captureRecipesUrl({ url: '' }, pageOf([]));
  renderApp('/');
  expect(await screen.findByText(/no recipes match/i)).toBeInTheDocument();
});

test('pagination renders page buttons from totalPages', async () => {
  captureRecipesUrl({ url: '' }, pageOf([recipeFixture()], { totalPages: 3, number: 0 }));
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  expect(screen.getByRole('button', { name: 'Go to page 3' })).toBeInTheDocument();
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/features/browse` — Expected: FAIL.

- [ ] **Step 3: Implement**

Create `src/components/StarDisplay.tsx`:

```tsx
export function StarDisplay({ average, count }: { average: number | null; count: number }) {
  if (average === null) return <span className="text-sm text-muted">No ratings yet</span>;
  return (
    <span className="text-sm font-semibold text-accent">
      ★ {average.toFixed(1)} <span className="font-normal text-muted">({count})</span>
    </span>
  );
}
```

Create `src/components/RecipeCard.tsx`:

```tsx
import { Link } from 'react-router';
import type { RecipeDisplay } from '@/lib/types';
import { StarDisplay } from './StarDisplay';

export function RecipeCard({ recipe }: { recipe: RecipeDisplay }) {
  return (
    <Link
      to={`/recipes/${recipe.id}`}
      className="group block border border-hairline transition-shadow hover:shadow-md"
    >
      {recipe.image ? (
        <img src={recipe.image} alt="" className="h-44 w-full object-cover" />
      ) : (
        <div className="flex h-44 w-full items-center justify-center bg-hairline text-4xl">🍽</div>
      )}
      <div className="p-3">
        <h3 className="font-bold leading-tight group-hover:text-accent">{recipe.title}</h3>
        <div className="mt-1 flex items-center gap-2">
          <StarDisplay average={recipe.averageRating} count={recipe.ratingCount} />
          {recipe.readyInMinutes !== null && (
            <span className="text-xs tracking-wide text-muted uppercase">{recipe.readyInMinutes} min</span>
          )}
        </div>
        {recipe.cuisines.length > 0 && (
          <p className="mt-1 text-xs tracking-widest text-muted uppercase">{recipe.cuisines.join(' · ')}</p>
        )}
      </div>
    </Link>
  );
}
```

Create `src/components/Pagination.tsx`:

```tsx
export function Pagination({
  page,
  totalPages,
  onPage,
}: {
  page: number;
  totalPages: number;
  onPage: (p: number) => void;
}) {
  if (totalPages <= 1) return null;
  return (
    <nav className="mt-8 flex justify-center gap-1">
      {Array.from({ length: totalPages }, (_, i) => (
        <button
          key={i}
          aria-label={`Go to page ${i + 1}`}
          aria-current={i === page ? 'page' : undefined}
          onClick={() => onPage(i)}
          className={`h-9 w-9 border text-sm font-bold ${
            i === page ? 'border-ink bg-ink text-white' : 'border-hairline hover:border-ink'
          }`}
        >
          {i + 1}
        </button>
      ))}
    </nav>
  );
}
```

Create `src/features/browse/FilterBar.tsx`:

```tsx
export const CUISINES = [
  'italian', 'mexican', 'chinese', 'indian', 'greek',
  'french', 'japanese', 'thai', 'spanish', 'american',
];

const DIETS = [
  ['vegan', 'Vegan'],
  ['vegetarian', 'Vegetarian'],
  ['glutenFree', 'Gluten free'],
  ['dairyFree', 'Dairy free'],
] as const;

export interface FilterValues {
  cuisine: string;
  vegan: boolean;
  vegetarian: boolean;
  glutenFree: boolean;
  dairyFree: boolean;
  maxReadyInMinutes: string;
  minRating: string;
}

export function FilterBar({
  values,
  onChange,
}: {
  values: FilterValues;
  onChange: (patch: Partial<FilterValues>) => void;
}) {
  return (
    <div className="mb-6 flex flex-wrap items-center gap-2 border-b border-hairline pb-4">
      <label className="text-xs font-bold tracking-widest uppercase">
        Cuisine{' '}
        <select
          aria-label="Cuisine"
          value={values.cuisine}
          onChange={(e) => onChange({ cuisine: e.target.value })}
          className="ml-1 border border-hairline px-2 py-1 text-sm font-normal normal-case"
        >
          <option value="">All</option>
          {CUISINES.map((c) => (
            <option key={c} value={c}>{c}</option>
          ))}
        </select>
      </label>

      {DIETS.map(([key, label]) => (
        <button
          key={key}
          onClick={() => onChange({ [key]: !values[key] } as Partial<FilterValues>)}
          className={`border px-3 py-1 text-xs font-bold tracking-wide uppercase ${
            values[key] ? 'border-ink bg-ink text-white' : 'border-hairline hover:border-ink'
          }`}
        >
          {label}
        </button>
      ))}

      <label className="text-xs font-bold tracking-widest uppercase">
        Max time{' '}
        <select
          aria-label="Max time"
          value={values.maxReadyInMinutes}
          onChange={(e) => onChange({ maxReadyInMinutes: e.target.value })}
          className="ml-1 border border-hairline px-2 py-1 text-sm font-normal normal-case"
        >
          <option value="">Any</option>
          {['15', '30', '45', '60'].map((m) => (
            <option key={m} value={m}>{m} min</option>
          ))}
        </select>
      </label>

      <label className="text-xs font-bold tracking-widest uppercase">
        Min rating{' '}
        <select
          aria-label="Min rating"
          value={values.minRating}
          onChange={(e) => onChange({ minRating: e.target.value })}
          className="ml-1 border border-hairline px-2 py-1 text-sm font-normal normal-case"
        >
          <option value="">Any</option>
          <option value="3">3+</option>
          <option value="4">4+</option>
          <option value="4.5">4.5+</option>
        </select>
      </label>
    </div>
  );
}
```

Replace `src/features/browse/BrowsePage.tsx`:

```tsx
import { useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { browseRecipes } from '@/api/recipes';
import type { RecipeFilter } from '@/lib/types';
import { RecipeCard } from '@/components/RecipeCard';
import { Pagination } from '@/components/Pagination';
import { Spinner, Button } from '@/components/ui';
import { FilterBar, type FilterValues } from './FilterBar';

function readFilters(params: URLSearchParams): FilterValues {
  return {
    cuisine: params.get('cuisine') ?? '',
    vegan: params.get('vegan') === 'true',
    vegetarian: params.get('vegetarian') === 'true',
    glutenFree: params.get('glutenFree') === 'true',
    dairyFree: params.get('dairyFree') === 'true',
    maxReadyInMinutes: params.get('maxReadyInMinutes') ?? '',
    minRating: params.get('minRating') ?? '',
  };
}

function toApiFilter(v: FilterValues): RecipeFilter {
  return {
    cuisine: v.cuisine || undefined,
    vegan: v.vegan || undefined,
    vegetarian: v.vegetarian || undefined,
    glutenFree: v.glutenFree || undefined,
    dairyFree: v.dairyFree || undefined,
    maxReadyInMinutes: v.maxReadyInMinutes ? Number(v.maxReadyInMinutes) : undefined,
    minRating: v.minRating ? Number(v.minRating) : undefined,
  };
}

export default function BrowsePage() {
  const [params, setParams] = useSearchParams();
  const values = readFilters(params);
  const page = Number(params.get('page') ?? '0');

  const query = useQuery({
    queryKey: ['recipes', params.toString()],
    queryFn: () => browseRecipes(toApiFilter(values), page),
  });

  function patchParams(patch: Partial<FilterValues>) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(patch)) {
      if (value === '' || value === false) next.delete(key);
      else next.set(key, String(value));
    }
    next.delete('page'); // filter change resets pagination
    setParams(next);
  }

  return (
    <div>
      <FilterBar values={values} onChange={patchParams} />

      {query.isPending && <Spinner />}
      {query.isError && (
        <div className="py-12 text-center">
          <p className="mb-4 text-sm text-red-600">Couldn't load recipes.</p>
          <Button variant="outline" onClick={() => query.refetch()}>Retry</Button>
        </div>
      )}
      {query.data && query.data.content.length === 0 && (
        <div className="py-12 text-center">
          <p className="mb-4 text-muted">No recipes match these filters.</p>
          <Button variant="outline" onClick={() => setParams(new URLSearchParams())}>Clear filters</Button>
        </div>
      )}
      {query.data && query.data.content.length > 0 && (
        <>
          <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4">
            {query.data.content.map((recipe) => (
              <RecipeCard key={recipe.id} recipe={recipe} />
            ))}
          </div>
          <Pagination
            page={query.data.page.number}
            totalPages={query.data.page.totalPages}
            onPage={(p) => {
              const next = new URLSearchParams(params);
              next.set('page', String(p));
              setParams(next);
            }}
          />
        </>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Run to verify green**

Run: `npx vitest run` — Expected: `11 passed`.

- [ ] **Step 5: Commit**

```bash
cd .. && git add -A recipe-app-frontend/src && git commit -m "feat: browse page with URL-driven filters and pagination"
```

---

### Task 5: Recipe detail + RatingBox

**Files:**
- Create: `src/features/recipe/RatingBox.tsx`
- Replace: `src/features/recipe/RecipeDetailPage.tsx`
- Test: `src/features/recipe/detail.test.tsx`

**Interfaces:**
- Consumes: `getRecipe`, `getMyRating`, `putMyRating`, `deleteMyRating`, `useAuth`, `StarDisplay`, `SectionHeading`, fixtures.
- Produces: `RecipeDetailPage` renders a `<section aria-label="comments">` placeholder `<CommentsSlot />` that Task 6 REPLACES with the real `CommentsSection` (exact replacement point marked with a comment). Owner Edit/Delete buttons come in Task 7.

- [ ] **Step 1: Write the failing test — `src/features/recipe/detail.test.tsx`**

```tsx
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, profileFixture, pageOf } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({
        data: { session: { access_token: 'test-token', user: { id: 'u-1', email: 'jane@example.com' } } },
      }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
    },
  },
}));

function stubDetail({ myRatingStatus = 200, myRating = 4 } = {}) {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(recipeFixture())),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      myRatingStatus === 200
        ? HttpResponse.json({ rating: myRating })
        : HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
  );
}

test('renders title, ingredients, structured steps, badges and macros', async () => {
  stubDetail();
  renderApp('/recipes/r-1');
  expect(await screen.findByRole('heading', { name: /bruschetta pork & pasta/i })).toBeInTheDocument();
  expect(screen.getByText('8 oz penne pasta')).toBeInTheDocument();
  expect(screen.getByText(/boil the pasta/i)).toBeInTheDocument();
  expect(screen.getByText(/gluten free/i)).toBeInTheDocument();
  expect(screen.getByText(/543/)).toBeInTheDocument(); // calories
  expect(screen.getByText(/from pink when/i)).toBeInTheDocument();
});

test('rating box pre-fills my rating and un-rates on clicking the same star', async () => {
  stubDetail({ myRating: 4 });
  let deleted = false;
  server.use(
    http.delete(`${BASE}/api/recipes/r-1/ratings/me`, () => {
      deleted = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderApp('/recipes/r-1');
  const star4 = await screen.findByRole('button', { name: /rate 4 stars/i });
  expect(star4).toHaveAttribute('aria-pressed', 'true');
  await userEvent.click(star4);
  await vi.waitFor(() => expect(deleted).toBe(true));
});

test('clicking a new star PUTs the rating', async () => {
  stubDetail({ myRatingStatus: 404 });
  let putBody: unknown = null;
  server.use(
    http.put(`${BASE}/api/recipes/r-1/ratings/me`, async ({ request }) => {
      putBody = await request.json();
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderApp('/recipes/r-1');
  await userEvent.click(await screen.findByRole('button', { name: /rate 5 stars/i }));
  await vi.waitFor(() => expect(putBody).toEqual({ rating: 5 }));
});

test('unknown recipe id shows not-found message', async () => {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/recipes/nope`, () =>
      HttpResponse.json({ status: 404, detail: 'Recipe not found' }, { status: 404 }),
    ),
  );
  renderApp('/recipes/nope');
  expect(await screen.findByText(/recipe not found/i)).toBeInTheDocument();
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/features/recipe` — Expected: FAIL.

- [ ] **Step 3: Implement**

Create `src/features/recipe/RatingBox.tsx`:

```tsx
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteMyRating, getMyRating, putMyRating } from '@/api/ratings';
import { useAuth } from '@/auth/AuthProvider';

export function RatingBox({ recipeId }: { recipeId: string }) {
  const { status } = useAuth();
  const queryClient = useQueryClient();

  const myRating = useQuery({
    queryKey: ['myRating', recipeId],
    queryFn: () => getMyRating(recipeId),
    enabled: status === 'ready',
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['myRating', recipeId] });
    void queryClient.invalidateQueries({ queryKey: ['recipe', recipeId] });
    void queryClient.invalidateQueries({ queryKey: ['recipes'] });
  };
  const rate = useMutation({ mutationFn: (n: number) => putMyRating(recipeId, n), onSuccess: invalidate });
  const unrate = useMutation({ mutationFn: () => deleteMyRating(recipeId), onSuccess: invalidate });

  if (status !== 'ready') {
    return <p className="text-xs text-muted">Sign in to rate this recipe.</p>;
  }
  const current = myRating.data ?? null;

  return (
    <div>
      <p className="mb-1 text-xs font-bold tracking-widest uppercase">Your rating</p>
      <div className="flex gap-1">
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            aria-label={`Rate ${n} stars`}
            aria-pressed={current === n}
            onClick={() => (current === n ? unrate.mutate() : rate.mutate(n))}
            className={`text-2xl leading-none ${
              current !== null && n <= current ? 'text-accent' : 'text-hairline hover:text-accent'
            }`}
          >
            ★
          </button>
        ))}
      </div>
      {current !== null && <p className="mt-1 text-xs text-muted">Click your star again to remove.</p>}
    </div>
  );
}
```

Replace `src/features/recipe/RecipeDetailPage.tsx`:

```tsx
import { Link, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { getRecipe } from '@/api/recipes';
import { ApiError } from '@/api/client';
import { StarDisplay } from '@/components/StarDisplay';
import { SectionHeading, Spinner } from '@/components/ui';
import type { RecipeDisplay } from '@/lib/types';
import { RatingBox } from './RatingBox';

// Task 6 replaces this placeholder with the real CommentsSection.
function CommentsSlot({ recipeId: _recipeId }: { recipeId: string }) {
  return <p className="text-sm text-muted">Comments coming soon.</p>;
}

function DietBadges({ recipe }: { recipe: RecipeDisplay }) {
  const badges = [
    recipe.vegan && 'Vegan',
    recipe.vegetarian && 'Vegetarian',
    recipe.glutenFree && 'Gluten free',
    recipe.dairyFree && 'Dairy free',
  ].filter(Boolean) as string[];
  if (badges.length === 0) return null;
  return (
    <div className="flex flex-wrap gap-1">
      {badges.map((b) => (
        <span key={b} className="border border-ink px-2 py-0.5 text-xs font-bold tracking-wide uppercase">{b}</span>
      ))}
    </div>
  );
}

export default function RecipeDetailPage() {
  const { id } = useParams<{ id: string }>();
  const query = useQuery({ queryKey: ['recipe', id], queryFn: () => getRecipe(id!), retry: false });

  if (query.isPending) return <Spinner />;
  if (query.isError) {
    const notFound = query.error instanceof ApiError && query.error.status === 404;
    return (
      <p className="py-12 text-center text-muted">
        {notFound ? 'Recipe not found.' : "Couldn't load this recipe."}
      </p>
    );
  }
  const recipe = query.data;

  return (
    <div className="flex flex-col gap-8 lg:flex-row">
      {/* Left: sticky facts card */}
      <aside className="lg:w-2/5">
        <div className="border border-hairline lg:sticky lg:top-4">
          {recipe.image ? (
            <img src={recipe.image} alt={recipe.title} className="w-full object-cover" />
          ) : (
            <div className="flex h-52 items-center justify-center bg-hairline text-5xl">🍽</div>
          )}
          <div className="space-y-3 p-4 text-sm">
            <div className="flex flex-wrap gap-x-4 gap-y-1 text-muted">
              {recipe.readyInMinutes !== null && <span>⏱ {recipe.readyInMinutes} min total</span>}
              {recipe.cookingMinutes !== null && <span>cook {recipe.cookingMinutes} min</span>}
              {recipe.servings !== null && <span>🍽 {recipe.servings} servings</span>}
            </div>
            <DietBadges recipe={recipe} />
            {recipe.calories !== null && (
              <p className="text-muted">
                {Math.round(recipe.calories)} kcal · {recipe.proteinGrams ?? '–'}g protein ·{' '}
                {recipe.fatGrams ?? '–'}g fat · {recipe.carbsGrams ?? '–'}g carbs
              </p>
            )}
            <div className="border-t border-hairline pt-3">
              <RatingBox recipeId={recipe.id} />
            </div>
          </div>
        </div>
      </aside>

      {/* Right: content */}
      <article className="flex-1">
        <h1 className="text-3xl font-extrabold tracking-tight">{recipe.title}</h1>
        <div className="mt-1 flex items-center gap-3">
          <StarDisplay average={recipe.averageRating} count={recipe.ratingCount} />
          {recipe.user ? (
            <Link to={`/users/${recipe.user.id}`} className="text-sm text-muted underline">
              by {recipe.user.firstName} {recipe.user.lastName}
            </Link>
          ) : recipe.sourceName ? (
            <a href={recipe.sourceUrl ?? '#'} target="_blank" rel="noreferrer" className="text-sm text-muted underline">
              From {recipe.sourceName} ↗
            </a>
          ) : null}
        </div>

        <SectionHeading>Ingredients</SectionHeading>
        <ul className="space-y-1">
          {recipe.ingredients.map((ing, i) => (
            <li key={i}>
              <label className="flex cursor-pointer items-baseline gap-2 text-sm">
                <input type="checkbox" className="accent-ink" />
                <span>{ing.originalText}</span>
              </label>
            </li>
          ))}
        </ul>

        <SectionHeading>Instructions</SectionHeading>
        {recipe.instructionsSteps.length > 0 ? (
          <ol className="space-y-3">
            {recipe.instructionsSteps.map((step) => (
              <li key={step.number} className="flex gap-3 text-sm">
                <span className="font-extrabold">{step.number}.</span>
                <span>{step.step}</span>
              </li>
            ))}
          </ol>
        ) : recipe.instructions ? (
          <p className="text-sm whitespace-pre-line">{recipe.instructions}</p>
        ) : (
          <p className="text-sm text-muted">No instructions provided.</p>
        )}

        <SectionHeading>Comments ({recipe.commentCount})</SectionHeading>
        <section aria-label="comments">
          <CommentsSlot recipeId={recipe.id} />
        </section>
      </article>
    </div>
  );
}
```

- [ ] **Step 4: Run to verify green**

Run: `npx vitest run` — Expected: `15 passed`.

- [ ] **Step 5: Commit**

```bash
cd .. && git add -A recipe-app-frontend/src && git commit -m "feat: recipe detail page with sticky facts card and rating box"
```

---

### Task 6: Comments + reactions + ConfirmDialog

**Files:**
- Create: `src/components/ConfirmDialog.tsx`, `src/features/recipe/CommentsSection.tsx`
- Modify: `src/features/recipe/RecipeDetailPage.tsx` (replace `CommentsSlot` with `CommentsSection` — delete the placeholder function, add the import, swap the JSX)
- Test: `src/features/recipe/comments.test.tsx`

**Interfaces:**
- Consumes: comments/reactions API modules, `useAuth`, `ConfirmDialog`, fixtures.
- Produces: `ConfirmDialog({open, title, description, confirmLabel, onConfirm, onCancel})` — reused by Task 7. `CommentsSection({recipeId, recipeOwnerId})`; `RecipeDetailPage` passes `recipeOwnerId={recipe.user?.id ?? null}`.

- [ ] **Step 1: Write the failing test — `src/features/recipe/comments.test.tsx`**

```tsx
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, profileFixture, commentFixture, pageOf } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({
        data: { session: { access_token: 'test-token', user: { id: 'u-1', email: 'jane@example.com' } } },
      }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
    },
  },
}));

const otherUser = { id: 'u-2', firstName: 'Bob', lastName: 'Smith' };

function stubPage({ comments = [commentFixture()], recipeUser = null as typeof otherUser | null } = {}) {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(recipeFixture({ user: recipeUser }))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf(comments))),
  );
}

test('posts a comment', async () => {
  stubPage();
  let posted: unknown = null;
  server.use(
    http.post(`${BASE}/api/recipes/r-1/comments`, async ({ request }) => {
      posted = await request.json();
      return HttpResponse.json(commentFixture({ id: 'c-2', body: 'Yum' }), { status: 201 });
    }),
  );
  renderApp('/recipes/r-1');
  await userEvent.type(await screen.findByPlaceholderText(/add a comment/i), 'Yum');
  await userEvent.click(screen.getByRole('button', { name: /post/i }));
  await vi.waitFor(() => expect(posted).toEqual({ body: 'Yum' }));
});

test('reaction thumb PUTs then toggles off with DELETE', async () => {
  stubPage();
  const calls: string[] = [];
  server.use(
    http.put(`${BASE}/api/comments/c-1/reactions/me`, () => {
      calls.push('put');
      return new HttpResponse(null, { status: 204 });
    }),
    http.delete(`${BASE}/api/comments/c-1/reactions/me`, () => {
      calls.push('delete');
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderApp('/recipes/r-1');
  const likeBtn = await screen.findByRole('button', { name: /like comment/i });
  await userEvent.click(likeBtn);
  await vi.waitFor(() => expect(calls).toEqual(['put']));
  await userEvent.click(likeBtn);
  await vi.waitFor(() => expect(calls).toEqual(['put', 'delete']));
});

test('own comment shows Edit and Delete; delete goes through the confirm dialog', async () => {
  stubPage({ comments: [commentFixture({ user: profileFixture })] });
  let deleted = false;
  server.use(
    http.delete(`${BASE}/api/comments/c-1`, () => {
      deleted = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderApp('/recipes/r-1');
  const comment = await screen.findByText('Delicious!');
  const item = comment.closest('li')!;
  await userEvent.click(within(item).getByRole('button', { name: /delete/i }));
  await userEvent.click(await screen.findByRole('button', { name: /confirm/i }));
  await vi.waitFor(() => expect(deleted).toBe(true));
});

test("someone else's comment shows no Edit, and Delete only when I own the recipe", async () => {
  // I (u-1) own the recipe; comment is by u-2 → Delete visible (moderation), Edit not.
  stubPage({
    comments: [commentFixture({ user: otherUser })],
    recipeUser: { ...profileFixture },
  });
  renderApp('/recipes/r-1');
  const comment = await screen.findByText('Delicious!');
  const item = comment.closest('li')!;
  expect(within(item).queryByRole('button', { name: /edit/i })).not.toBeInTheDocument();
  expect(within(item).getByRole('button', { name: /delete/i })).toBeInTheDocument();
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/features/recipe/comments.test.tsx` — Expected: FAIL.

- [ ] **Step 3: Implement**

Create `src/components/ConfirmDialog.tsx`:

```tsx
import * as Dialog from '@radix-ui/react-dialog';
import { Button } from './ui';

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = 'Confirm',
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: string;
  description: string;
  confirmLabel?: string;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={(o) => !o && onCancel()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 bg-black/40" />
        <Dialog.Content className="fixed top-1/2 left-1/2 w-80 -translate-x-1/2 -translate-y-1/2 border border-ink bg-white p-5">
          <Dialog.Title className="font-extrabold">{title}</Dialog.Title>
          <Dialog.Description className="mt-2 text-sm text-muted">{description}</Dialog.Description>
          <div className="mt-4 flex justify-end gap-2">
            <Button variant="outline" onClick={onCancel}>Cancel</Button>
            <Button variant="danger" onClick={onConfirm}>{confirmLabel}</Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
```

Create `src/features/recipe/CommentsSection.tsx`:

```tsx
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { addComment, deleteComment, editComment, getComments } from '@/api/comments';
import { deleteMyReaction, putMyReaction } from '@/api/reactions';
import { useAuth } from '@/auth/AuthProvider';
import { ApiError } from '@/api/client';
import { Button, Spinner } from '@/components/ui';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { Pagination } from '@/components/Pagination';
import type { CommentDisplay } from '@/lib/types';

function ReactionButtons({ comment }: { comment: CommentDisplay }) {
  const { status } = useAuth();
  // The API has no "my reaction" read endpoint; we track the reaction made
  // in THIS session locally. Unknown initial state: first click always PUTs.
  const [mine, setMine] = useState<boolean | null>(null);
  const queryClient = useQueryClient();
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ['comments'] });

  const react = useMutation({
    mutationFn: (isLike: boolean) => putMyReaction(comment.id, isLike),
    onSuccess: (_d, isLike) => { setMine(isLike); invalidate(); },
  });
  const unreact = useMutation({
    mutationFn: () => deleteMyReaction(comment.id),
    onSuccess: () => { setMine(null); invalidate(); },
  });

  const disabled = status !== 'ready';
  const btn = (isLike: boolean, count: number, label: string, glyph: string) => (
    <button
      aria-label={label}
      disabled={disabled}
      onClick={() => (mine === isLike ? unreact.mutate() : react.mutate(isLike))}
      className={`text-xs ${mine === isLike ? 'font-bold text-accent' : 'text-muted hover:text-ink'} disabled:opacity-40`}
    >
      {glyph} {count}
    </button>
  );
  return (
    <span className="flex gap-3">
      {btn(true, comment.likeCount, `Like comment`, '👍')}
      {btn(false, comment.dislikeCount, `Dislike comment`, '👎')}
    </span>
  );
}

export function CommentsSection({
  recipeId,
  recipeOwnerId,
}: {
  recipeId: string;
  recipeOwnerId: string | null;
}) {
  const { status, profile } = useAuth();
  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState('');
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: ['comments', recipeId, page],
    queryFn: () => getComments(recipeId, page),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['comments', recipeId] });
    void queryClient.invalidateQueries({ queryKey: ['recipe', recipeId] });
  };
  const onError = (e: unknown) => toast.error(e instanceof ApiError ? e.detail : 'Something went wrong');

  const post = useMutation({
    mutationFn: () => addComment(recipeId, draft),
    onSuccess: () => { setDraft(''); invalidate(); },
    onError,
  });
  const saveEdit = useMutation({
    mutationFn: () => editComment(editingId!, editDraft),
    onSuccess: () => { setEditingId(null); invalidate(); },
    onError,
  });
  const remove = useMutation({
    mutationFn: (id: string) => deleteComment(id),
    onSuccess: () => { setDeletingId(null); invalidate(); },
    onError,
  });

  if (query.isPending) return <Spinner />;
  if (query.isError) return <p className="text-sm text-red-600">Couldn't load comments.</p>;

  return (
    <div>
      {status === 'ready' ? (
        <form
          className="mb-6 flex gap-2"
          onSubmit={(e) => { e.preventDefault(); if (draft.trim()) post.mutate(); }}
        >
          <input
            placeholder="Add a comment…"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            maxLength={5000}
            className="flex-1 border border-hairline px-3 py-2 text-sm focus:border-ink focus:outline-none"
          />
          <Button type="submit" disabled={post.isPending || !draft.trim()}>Post</Button>
        </form>
      ) : (
        <p className="mb-6 text-sm text-muted">Sign in to join the conversation.</p>
      )}

      <ul className="space-y-4">
        {query.data.content.map((comment) => {
          const isAuthor = profile?.id === comment.user.id;
          const canDelete = isAuthor || (recipeOwnerId !== null && profile?.id === recipeOwnerId);
          return (
            <li key={comment.id} className="border-b border-hairline pb-3">
              <p className="text-xs font-bold tracking-wide uppercase">
                {comment.user.firstName} {comment.user.lastName}
              </p>
              {editingId === comment.id ? (
                <form
                  className="mt-1 flex gap-2"
                  onSubmit={(e) => { e.preventDefault(); saveEdit.mutate(); }}
                >
                  <input
                    value={editDraft}
                    onChange={(e) => setEditDraft(e.target.value)}
                    className="flex-1 border border-hairline px-2 py-1 text-sm"
                  />
                  <Button type="submit">Save</Button>
                  <Button type="button" variant="outline" onClick={() => setEditingId(null)}>Cancel</Button>
                </form>
              ) : (
                <p className="mt-1 text-sm">{comment.body}</p>
              )}
              <div className="mt-2 flex items-center gap-4">
                <ReactionButtons comment={comment} />
                {isAuthor && editingId !== comment.id && (
                  <button
                    className="text-xs text-muted underline hover:text-ink"
                    onClick={() => { setEditingId(comment.id); setEditDraft(comment.body); }}
                  >
                    Edit
                  </button>
                )}
                {canDelete && (
                  <button
                    className="text-xs text-muted underline hover:text-red-600"
                    onClick={() => setDeletingId(comment.id)}
                  >
                    Delete
                  </button>
                )}
              </div>
            </li>
          );
        })}
        {query.data.content.length === 0 && <p className="text-sm text-muted">No comments yet.</p>}
      </ul>

      <Pagination page={query.data.page.number} totalPages={query.data.page.totalPages} onPage={setPage} />

      <ConfirmDialog
        open={deletingId !== null}
        title="Delete comment?"
        description="This can't be undone."
        confirmLabel="Confirm"
        onConfirm={() => deletingId && remove.mutate(deletingId)}
        onCancel={() => setDeletingId(null)}
      />
    </div>
  );
}
```

Modify `src/features/recipe/RecipeDetailPage.tsx`: delete the `CommentsSlot` placeholder function, add `import { CommentsSection } from './CommentsSection';`, and swap the comments JSX to:

```tsx
        <section aria-label="comments">
          <CommentsSection recipeId={recipe.id} recipeOwnerId={recipe.user?.id ?? null} />
        </section>
```

- [ ] **Step 4: Run to verify green**

Run: `npx vitest run` — Expected: `19 passed`.

- [ ] **Step 5: Commit**

```bash
cd .. && git add -A recipe-app-frontend/src && git commit -m "feat: comments with reactions, editing, and moderated deletion"
```

---

### Task 7: Recipe authoring + owner actions

**Files:**
- Replace: `src/features/authoring/RecipeFormPage.tsx`
- Modify: `src/features/recipe/RecipeDetailPage.tsx` (owner Edit/Delete buttons next to the title)
- Test: `src/features/authoring/authoring.test.tsx`

**Interfaces:**
- Consumes: `createRecipe`, `updateRecipe`, `deleteRecipe`, `getRecipe`, `CUISINES`, `ConfirmDialog`, `useAuth`, zod + react-hook-form.
- Produces: complete authoring flow. Detail page gains an `Edit` link (`/recipes/:id/edit`) and `Delete` button visible only when `profile.id === recipe.user?.id`.

- [ ] **Step 1: Write the failing test — `src/features/authoring/authoring.test.tsx`**

```tsx
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, profileFixture, pageOf } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({
        data: { session: { access_token: 'test-token', user: { id: 'u-1', email: 'jane@example.com' } } },
      }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
    },
  },
}));

function stubMe() {
  server.use(http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)));
}

test('create: fills the form, adds an ingredient row, POSTs, navigates to the new recipe', async () => {
  stubMe();
  let posted: any = null;
  const created = recipeFixture({ id: 'r-new', title: 'My Pie', user: profileFixture });
  server.use(
    http.post(`${BASE}/api/recipes`, async ({ request }) => {
      posted = await request.json();
      return HttpResponse.json(created, { status: 201 });
    }),
    http.get(`${BASE}/api/recipes/r-new`, () => HttpResponse.json(created)),
    http.get(`${BASE}/api/recipes/r-new/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-new/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
  );
  renderApp('/recipes/new');

  await userEvent.type(await screen.findByLabelText(/title/i), 'My Pie');
  await userEvent.type(screen.getByLabelText(/ingredient 1 name/i), 'apples');
  await userEvent.type(screen.getByLabelText(/ingredient 1 text/i), '3 tart apples, sliced');
  await userEvent.click(screen.getByRole('button', { name: /add ingredient/i }));
  await userEvent.type(screen.getByLabelText(/ingredient 2 name/i), 'sugar');
  await userEvent.type(screen.getByLabelText(/ingredient 2 text/i), '1 cup sugar');
  await userEvent.click(screen.getByRole('button', { name: /^save recipe$/i }));

  expect(await screen.findByRole('heading', { name: /my pie/i })).toBeInTheDocument();
  expect(posted.title).toBe('My Pie');
  expect(posted.ingredients).toEqual([
    { ingredientName: 'apples', originalText: '3 tart apples, sliced' },
    { ingredientName: 'sugar', originalText: '1 cup sugar' },
  ]);
});

test('create: empty title and no ingredients show inline validation, no request sent', async () => {
  stubMe();
  let posted = false;
  server.use(http.post(`${BASE}/api/recipes`, () => { posted = true; return HttpResponse.json({}); }));
  renderApp('/recipes/new');
  await userEvent.click(await screen.findByRole('button', { name: /^save recipe$/i }));
  expect(await screen.findAllByRole('alert')).not.toHaveLength(0);
  expect(posted).toBe(false);
});

test('server field errors from ProblemDetail map onto the form', async () => {
  stubMe();
  server.use(
    http.post(`${BASE}/api/recipes`, () =>
      HttpResponse.json(
        { status: 400, detail: 'Validation failed', errors: { title: 'must not be blank' } },
        { status: 400 },
      ),
    ),
  );
  renderApp('/recipes/new');
  await userEvent.type(await screen.findByLabelText(/title/i), 'X');
  await userEvent.type(screen.getByLabelText(/ingredient 1 name/i), 'a');
  await userEvent.type(screen.getByLabelText(/ingredient 1 text/i), 'b');
  await userEvent.click(screen.getByRole('button', { name: /^save recipe$/i }));
  expect(await screen.findByText('must not be blank')).toBeInTheDocument();
});

test('owner sees Edit and Delete on their recipe detail; delete confirms then navigates home', async () => {
  stubMe();
  let deleted = false;
  server.use(
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(recipeFixture({ user: profileFixture }))),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
    http.delete(`${BASE}/api/recipes/r-1`, () => {
      deleted = true;
      return new HttpResponse(null, { status: 204 });
    }),
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
  );
  renderApp('/recipes/r-1');
  await userEvent.click(await screen.findByRole('button', { name: /delete recipe/i }));
  await userEvent.click(await screen.findByRole('button', { name: /confirm/i }));
  await vi.waitFor(() => expect(deleted).toBe(true));
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/features/authoring` — Expected: FAIL.

- [ ] **Step 3: Implement**

Replace `src/features/authoring/RecipeFormPage.tsx`:

```tsx
import { useEffect } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useFieldArray, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { toast } from 'sonner';
import { createRecipe, getRecipe, updateRecipe } from '@/api/recipes';
import { ApiError } from '@/api/client';
import { Button, Field, SectionHeading, Spinner, TextInput } from '@/components/ui';
import { CUISINES } from '@/features/browse/FilterBar';
import type { RecipeCreate } from '@/lib/types';

const schema = z.object({
  title: z.string().min(1, 'Title is required').max(255),
  image: z.string().max(2048),
  servings: z.string(),
  readyInMinutes: z.string(),
  cookingMinutes: z.string(),
  preparationMinutes: z.string(),
  sourceName: z.string().max(255),
  sourceUrl: z.string().max(2048),
  instructions: z.string().max(50000),
  steps: z.array(z.object({ text: z.string().min(1, 'Step text required').max(5000) })).max(100),
  dairyFree: z.boolean(),
  glutenFree: z.boolean(),
  vegan: z.boolean(),
  vegetarian: z.boolean(),
  cuisines: z.array(z.string()).max(10),
  ingredients: z
    .array(
      z.object({
        ingredientName: z.string().min(1, 'Name required').max(255),
        originalText: z.string().min(1, 'Text required').max(500),
      }),
    )
    .min(1, 'At least one ingredient')
    .max(100),
});
type FormValues = z.infer<typeof schema>;

const emptyValues: FormValues = {
  title: '', image: '', servings: '', readyInMinutes: '', cookingMinutes: '',
  preparationMinutes: '', sourceName: '', sourceUrl: '', instructions: '',
  steps: [], dairyFree: false, glutenFree: false, vegan: false, vegetarian: false,
  cuisines: [], ingredients: [{ ingredientName: '', originalText: '' }],
};

const toInt = (s: string) => (s.trim() === '' ? null : Number(s));
const toNull = (s: string) => (s.trim() === '' ? null : s);

function toDto(v: FormValues): RecipeCreate {
  return {
    title: v.title,
    image: toNull(v.image),
    servings: toInt(v.servings),
    readyInMinutes: toInt(v.readyInMinutes),
    cookingMinutes: toInt(v.cookingMinutes),
    preparationMinutes: toInt(v.preparationMinutes),
    sourceName: toNull(v.sourceName),
    sourceUrl: toNull(v.sourceUrl),
    instructions: toNull(v.instructions),
    instructionsSteps: v.steps.map((s, i) => ({ number: i + 1, step: s.text, ingredients: [], equipment: [] })),
    dairyFree: v.dairyFree, glutenFree: v.glutenFree, vegan: v.vegan, vegetarian: v.vegetarian,
    cuisines: v.cuisines,
    ingredients: v.ingredients,
  };
}

export default function RecipeFormPage() {
  const { id } = useParams<{ id: string }>(); // present in edit mode only
  const navigate = useNavigate();

  const existing = useQuery({
    queryKey: ['recipe', id],
    queryFn: () => getRecipe(id!),
    enabled: !!id,
  });

  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: emptyValues });
  const { register, handleSubmit, control, reset, setError, watch, formState: { errors, isSubmitting } } = form;
  const ingredients = useFieldArray({ control, name: 'ingredients' });
  const steps = useFieldArray({ control, name: 'steps' });
  const imageUrl = watch('image');

  useEffect(() => {
    if (existing.data) {
      const r = existing.data;
      reset({
        title: r.title, image: r.image ?? '', servings: r.servings?.toString() ?? '',
        readyInMinutes: r.readyInMinutes?.toString() ?? '', cookingMinutes: r.cookingMinutes?.toString() ?? '',
        preparationMinutes: r.preparationMinutes?.toString() ?? '', sourceName: r.sourceName ?? '',
        sourceUrl: r.sourceUrl ?? '', instructions: r.instructions ?? '',
        steps: r.instructionsSteps.map((s) => ({ text: s.step })),
        dairyFree: r.dairyFree, glutenFree: r.glutenFree, vegan: r.vegan, vegetarian: r.vegetarian,
        cuisines: r.cuisines,
        ingredients: r.ingredients.length
          ? r.ingredients.map((i) => ({ ingredientName: i.ingredientName, originalText: i.originalText }))
          : [{ ingredientName: '', originalText: '' }],
      });
    }
  }, [existing.data, reset]);

  async function onSubmit(values: FormValues) {
    try {
      const saved = id ? await updateRecipe(id, toDto(values)) : await createRecipe(toDto(values));
      navigate(`/recipes/${saved.id}`);
    } catch (e) {
      if (e instanceof ApiError && e.errors) {
        for (const [field, message] of Object.entries(e.errors)) {
          // backend field names match ours except instructionsSteps→steps
          const name = field.startsWith('instructionsSteps') ? 'steps' : field;
          setError(name as never, { message });
        }
      } else {
        toast.error(e instanceof ApiError ? e.detail : 'Something went wrong');
      }
    }
  }

  if (id && existing.isPending) return <Spinner />;

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="mx-auto max-w-2xl">
      <h1 className="text-2xl font-extrabold tracking-tight">{id ? 'Edit recipe' : 'New recipe'}</h1>

      <SectionHeading>Basics</SectionHeading>
      <div className="space-y-4">
        <Field label="Title" error={errors.title?.message}>
          <TextInput aria-label="Title" {...register('title')} />
        </Field>
        <Field label="Image URL" error={errors.image?.message}>
          <TextInput aria-label="Image URL" {...register('image')} />
        </Field>
        {imageUrl && <img src={imageUrl} alt="Preview" className="h-40 object-cover" />}
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Field label="Servings"><TextInput type="number" min={0} {...register('servings')} /></Field>
          <Field label="Ready (min)"><TextInput type="number" min={0} {...register('readyInMinutes')} /></Field>
          <Field label="Cook (min)"><TextInput type="number" min={0} {...register('cookingMinutes')} /></Field>
          <Field label="Prep (min)"><TextInput type="number" min={0} {...register('preparationMinutes')} /></Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Source name"><TextInput {...register('sourceName')} /></Field>
          <Field label="Source URL"><TextInput {...register('sourceUrl')} /></Field>
        </div>
      </div>

      <SectionHeading>Tags</SectionHeading>
      <div className="flex flex-wrap gap-4 text-sm">
        {(['vegan', 'vegetarian', 'glutenFree', 'dairyFree'] as const).map((flag) => (
          <label key={flag} className="flex items-center gap-1">
            <input type="checkbox" className="accent-ink" {...register(flag)} /> {flag}
          </label>
        ))}
      </div>
      <div className="mt-3 flex flex-wrap gap-3 text-sm">
        {CUISINES.map((c) => (
          <label key={c} className="flex items-center gap-1">
            <input type="checkbox" value={c} className="accent-ink" {...register('cuisines')} /> {c}
          </label>
        ))}
      </div>

      <SectionHeading>Ingredients</SectionHeading>
      {errors.ingredients?.message && <p role="alert" className="mb-2 text-xs text-red-600">{errors.ingredients.message}</p>}
      <div className="space-y-2">
        {ingredients.fields.map((field, i) => (
          <div key={field.id} className="flex gap-2">
            <div className="w-1/3">
              <TextInput
                aria-label={`Ingredient ${i + 1} name`}
                placeholder="Name (e.g. flour)"
                {...register(`ingredients.${i}.ingredientName`)}
              />
              {errors.ingredients?.[i]?.ingredientName && (
                <span role="alert" className="text-xs text-red-600">{errors.ingredients[i]?.ingredientName?.message}</span>
              )}
            </div>
            <div className="flex-1">
              <TextInput
                aria-label={`Ingredient ${i + 1} text`}
                placeholder="As written (e.g. 2 cups flour, sifted)"
                {...register(`ingredients.${i}.originalText`)}
              />
              {errors.ingredients?.[i]?.originalText && (
                <span role="alert" className="text-xs text-red-600">{errors.ingredients[i]?.originalText?.message}</span>
              )}
            </div>
            <Button type="button" variant="outline" onClick={() => ingredients.remove(i)} disabled={ingredients.fields.length === 1}>✕</Button>
          </div>
        ))}
      </div>
      <Button type="button" variant="outline" className="mt-2" onClick={() => ingredients.append({ ingredientName: '', originalText: '' })}>
        Add ingredient
      </Button>

      <SectionHeading>Instructions</SectionHeading>
      <Field label="Free-text instructions" error={errors.instructions?.message}>
        <textarea
          rows={4}
          className="w-full border border-hairline px-3 py-2 text-sm focus:border-ink focus:outline-none"
          {...register('instructions')}
        />
      </Field>
      <p className="mt-3 mb-1 text-xs font-bold tracking-widest uppercase">Structured steps (optional)</p>
      <div className="space-y-2">
        {steps.fields.map((field, i) => (
          <div key={field.id} className="flex gap-2">
            <span className="pt-2 text-sm font-extrabold">{i + 1}.</span>
            <div className="flex-1">
              <TextInput aria-label={`Step ${i + 1}`} {...register(`steps.${i}.text`)} />
              {errors.steps?.[i]?.text && (
                <span role="alert" className="text-xs text-red-600">{errors.steps[i]?.text?.message}</span>
              )}
            </div>
            <Button type="button" variant="outline" onClick={() => steps.remove(i)}>✕</Button>
          </div>
        ))}
      </div>
      <Button type="button" variant="outline" className="mt-2" onClick={() => steps.append({ text: '' })}>
        Add step
      </Button>

      <div className="mt-8 border-t-2 border-ink pt-4">
        <Button type="submit" disabled={isSubmitting}>Save recipe</Button>
      </div>
    </form>
  );
}
```

Modify `src/features/recipe/RecipeDetailPage.tsx` — add owner actions. Add imports:

```tsx
import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { toast } from 'sonner';
import { deleteRecipe } from '@/api/recipes';
import { useAuth } from '@/auth/AuthProvider';
import { Button } from '@/components/ui';
import { ConfirmDialog } from '@/components/ConfirmDialog';
```

Inside the component (after `const recipe = query.data;` — hooks must go ABOVE the early returns, so place `useAuth`, `useState`, `useNavigate`, and the mutation at the top of the component with the other hooks):

```tsx
  const { profile } = useAuth();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const navigate = useNavigate();
  const removeRecipe = useMutation({
    mutationFn: (recipeId: string) => deleteRecipe(recipeId),
    onSuccess: () => navigate('/'),
    onError: () => toast.error("Couldn't delete the recipe"),
  });
```

And in the JSX, right after the `<h1>` title line, add:

```tsx
        {profile && recipe.user?.id === profile.id && (
          <div className="mt-2 flex gap-2">
            <Link to={`/recipes/${recipe.id}/edit`} className="border border-ink px-3 py-1 text-xs font-bold tracking-wide uppercase hover:bg-ink hover:text-white">
              Edit
            </Link>
            <Button variant="danger" className="!px-3 !py-1 !text-xs" onClick={() => setConfirmDelete(true)}>
              Delete recipe
            </Button>
            <ConfirmDialog
              open={confirmDelete}
              title="Delete recipe?"
              description="The recipe, its ratings and its comments will be permanently removed."
              confirmLabel="Confirm"
              onConfirm={() => removeRecipe.mutate(recipe.id)}
              onCancel={() => setConfirmDelete(false)}
            />
          </div>
        )}
```

- [ ] **Step 4: Run to verify green**

Run: `npx vitest run` — Expected: `23 passed`.

- [ ] **Step 5: Commit**

```bash
cd .. && git add -A recipe-app-frontend/src && git commit -m "feat: recipe authoring form and owner edit/delete actions"
```

---

### Task 8: Profile pages

**Files:**
- Replace: `src/features/profile/MyProfilePage.tsx`, `src/features/profile/PublicProfilePage.tsx`
- Test: `src/features/profile/profiles.test.tsx`

**Interfaces:**
- Consumes: `getUser`, `getUserRecipes`, `updateMe`, `useAuth`, `RecipeCard`, `Pagination`, fixtures.
- Produces: final pages; nothing downstream.

- [ ] **Step 1: Write the failing test — `src/features/profile/profiles.test.tsx`**

```tsx
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, profileFixture, pageOf } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({
        data: { session: { access_token: 'test-token', user: { id: 'u-1', email: 'jane@example.com' } } },
      }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
    },
  },
}));

test('public profile shows the user name and their recipes', async () => {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/users/u-2`, () =>
      HttpResponse.json({ id: 'u-2', firstName: 'Bob', lastName: 'Smith' }),
    ),
    http.get(`${BASE}/api/users/u-2/recipes`, () =>
      HttpResponse.json(pageOf([recipeFixture({ title: 'Bob Burgers' })])),
    ),
  );
  renderApp('/users/u-2');
  expect(await screen.findByRole('heading', { name: /bob smith/i })).toBeInTheDocument();
  expect(await screen.findByText('Bob Burgers')).toBeInTheDocument();
});

test('my profile edits names via PUT', async () => {
  let putBody: unknown = null;
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/users/u-1/recipes`, () => HttpResponse.json(pageOf([]))),
    http.put(`${BASE}/api/users/me`, async ({ request }) => {
      putBody = await request.json();
      return HttpResponse.json({ id: 'u-1', firstName: 'Janet', lastName: 'Doe' });
    }),
  );
  renderApp('/me');
  const first = await screen.findByLabelText(/first name/i);
  await userEvent.clear(first);
  await userEvent.type(first, 'Janet');
  await userEvent.click(screen.getByRole('button', { name: /save names/i }));
  await vi.waitFor(() => expect(putBody).toEqual({ firstName: 'Janet', lastName: 'Doe' }));
  expect(await screen.findByRole('button', { name: /janet/i })).toBeInTheDocument(); // header updates
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/features/profile/profiles.test.tsx` — Expected: FAIL.

- [ ] **Step 3: Implement**

Replace `src/features/profile/PublicProfilePage.tsx`:

```tsx
import { useState } from 'react';
import { useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { getUser, getUserRecipes } from '@/api/users';
import { RecipeCard } from '@/components/RecipeCard';
import { Pagination } from '@/components/Pagination';
import { SectionHeading, Spinner } from '@/components/ui';

export default function PublicProfilePage() {
  const { id } = useParams<{ id: string }>();
  const [page, setPage] = useState(0);
  const user = useQuery({ queryKey: ['user', id], queryFn: () => getUser(id!) });
  const recipes = useQuery({
    queryKey: ['userRecipes', id, page],
    queryFn: () => getUserRecipes(id!, page),
  });

  if (user.isPending) return <Spinner />;
  if (user.isError) return <p className="py-12 text-center text-muted">User not found.</p>;

  return (
    <div>
      <h1 className="text-2xl font-extrabold tracking-tight">
        {user.data.firstName} {user.data.lastName}
      </h1>
      <SectionHeading>Recipes</SectionHeading>
      {recipes.isPending && <Spinner />}
      {recipes.data && recipes.data.content.length === 0 && (
        <p className="text-sm text-muted">No recipes published yet.</p>
      )}
      {recipes.data && recipes.data.content.length > 0 && (
        <>
          <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {recipes.data.content.map((r) => <RecipeCard key={r.id} recipe={r} />)}
          </div>
          <Pagination page={recipes.data.page.number} totalPages={recipes.data.page.totalPages} onPage={setPage} />
        </>
      )}
    </div>
  );
}
```

Replace `src/features/profile/MyProfilePage.tsx`:

```tsx
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { updateMe, getUserRecipes } from '@/api/users';
import { useAuth } from '@/auth/AuthProvider';
import { ApiError } from '@/api/client';
import { RecipeCard } from '@/components/RecipeCard';
import { Pagination } from '@/components/Pagination';
import { Button, Field, SectionHeading, Spinner, TextInput } from '@/components/ui';

export default function MyProfilePage() {
  const { profile, setProfile } = useAuth();
  const [firstName, setFirstName] = useState(profile?.firstName ?? '');
  const [lastName, setLastName] = useState(profile?.lastName ?? '');
  const [busy, setBusy] = useState(false);
  const [page, setPage] = useState(0);

  const recipes = useQuery({
    queryKey: ['userRecipes', profile?.id, page],
    queryFn: () => getUserRecipes(profile!.id, page),
    enabled: !!profile,
  });

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      const updated = await updateMe({ firstName, lastName });
      setProfile(updated);
      toast.success('Profile updated');
    } catch (err) {
      toast.error(err instanceof ApiError ? err.detail : 'Something went wrong');
    } finally {
      setBusy(false);
    }
  }

  if (!profile) return <Spinner />;

  return (
    <div>
      <h1 className="text-2xl font-extrabold tracking-tight">
        {profile.firstName} {profile.lastName}
      </h1>

      <SectionHeading>Profile</SectionHeading>
      <form onSubmit={onSubmit} className="flex max-w-lg flex-wrap items-end gap-3">
        <Field label="First name">
          <TextInput value={firstName} onChange={(e) => setFirstName(e.target.value)} required maxLength={255} />
        </Field>
        <Field label="Last name">
          <TextInput value={lastName} onChange={(e) => setLastName(e.target.value)} required maxLength={255} />
        </Field>
        <Button type="submit" disabled={busy}>Save names</Button>
      </form>

      <SectionHeading>My recipes</SectionHeading>
      {recipes.isPending && <Spinner />}
      {recipes.data && recipes.data.content.length === 0 && (
        <p className="text-sm text-muted">
          Nothing yet — <Link to="/recipes/new" className="text-accent underline">publish your first recipe</Link>.
        </p>
      )}
      {recipes.data && recipes.data.content.length > 0 && (
        <>
          <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {recipes.data.content.map((r) => <RecipeCard key={r.id} recipe={r} />)}
          </div>
          <Pagination page={recipes.data.page.number} totalPages={recipes.data.page.totalPages} onPage={setPage} />
        </>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Run to verify green**

Run: `npx vitest run` — Expected: `25 passed`.

- [ ] **Step 5: Commit**

```bash
cd .. && git add -A recipe-app-frontend/src && git commit -m "feat: my-profile and public-profile pages"
```

---

### Task 9: Full verification + live smoke setup

**Files:** none created — verification + user handoff.

- [ ] **Step 1: Full suite + production build**

Run: `npx vitest run` — Expected: `25 passed`, 7 test files.
Run: `npm run build` — Expected: clean tsc + vite build.

- [ ] **Step 2: Create `.env.local` for the live run**

Create `recipe-app-frontend/.env.local` (gitignored automatically via `*.local`):

```
VITE_SUPABASE_URL=https://bwnbvtbhcmeifsmlvmgl.supabase.co
VITE_SUPABASE_ANON_KEY=PASTE_YOUR_ANON_KEY_HERE
VITE_API_BASE_URL=http://localhost:8080
```

(The Supabase project URL is the one already configured in the backend's `application.properties` JWKS setting. The anon key placeholder is for the USER to fill from Supabase dashboard → Settings → API → anon public key. Never commit a real key.)

- [ ] **Step 3: Report the manual smoke checklist (do not attempt it yourself — the user runs it)**

The user, with the backend running in IntelliJ and the anon key pasted:

```bash
cd recipe-app-frontend && npm run dev
```

Then at http://localhost:5173: browse shows seeded recipes → filters narrow them → detail renders → sign up → complete profile → rate, comment, react → create a recipe → edit it → delete it.

- [ ] **Step 4: Verify clean tree and commit anything pending**

Run: `git status --short` — Expected: nothing unstaged under `recipe-app-frontend/` (`.env.local` is ignored).
