import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { pageOf, profileFixture, recipeFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import type { MyProfile, RecipeDisplay, RecipeReview } from '@/lib/types';

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

vi.mock('@/api/photos', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/photos')>()),
  deletePhotos: vi.fn(async () => {}),
}));

const LIVE_TOAST = 'Your recipe goes live as soon as you save it.';
const REJECTED_BANNER = 'Not published. Edit it to publish it.';
const HIDDEN_BANNER = 'Hidden while an admin looks at reports.';

function review(overrides: Partial<RecipeReview> = {}): RecipeReview {
  return {
    status: 'approved', rejectionReason: null, everApproved: true, revision: 1,
    submittedAt: '2026-10-18T10:00:00Z', hidden: false, ...overrides,
  };
}

function signedInAs(profile: MyProfile = profileFixture) {
  server.use(http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profile)));
}

/** The detail-page requests a recipe by the signed-in author makes. */
function serveRecipe(recipe: RecipeDisplay) {
  server.use(
    http.get(`${BASE}/api/recipes/${recipe.id}`, () => HttpResponse.json(recipe)),
    http.get(`${BASE}/api/recipes/${recipe.id}/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/${recipe.id}/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
    http.get(`${BASE}/api/users/me/saved/ids`, () => HttpResponse.json([])),
  );
}

/** What the backend returns to the author right after create: the recipe with its review block. */
function createdBy(profile: MyProfile) {
  return recipeFixture({
    id: 'r-new', title: 'My Pie', user: profile,
    review: review(),
  });
}

async function createRecipeAs(profile: MyProfile) {
  signedInAs(profile);
  const created = createdBy(profile);
  const detailGets = { count: 0 };
  server.use(
    http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json([])),
    http.post(`${BASE}/api/recipes`, () => HttpResponse.json(created, { status: 201 })),
  );
  serveRecipe(created);
  server.use(
    http.get(`${BASE}/api/recipes/r-new`, () => {
      detailGets.count++;
      return HttpResponse.json(created);
    }),
  );
  renderApp('/recipes/new');
  await userEvent.type(await screen.findByLabelText(/title/i), 'My Pie');
  await userEvent.type(screen.getByLabelText('Ingredient 1'), 'apples');
  await userEvent.type(screen.getByLabelText('Ingredient 1 amount and notes'), '3 tart apples');
  await userEvent.type(screen.getByLabelText('Servings'), '2');
  await userEvent.click(screen.getByRole('button', { name: /^save recipe$/i }));
  expect(await screen.findByRole('heading', { name: /my pie/i })).toBeInTheDocument();
  return detailGets;
}

test('createShowsLiveToast', async () => {
  await createRecipeAs(profileFixture);
  expect(await screen.findByText(LIVE_TOAST)).toBeInTheDocument();
  expectNoEmoji();
});

test('adminCreateShowsTheSameToast', async () => {
  await createRecipeAs({ ...profileFixture, isAdmin: true });
  expect(await screen.findByText(LIVE_TOAST)).toBeInTheDocument();
});

test('createShowsNoBannerAndKeepsFeedbackControls', async () => {
  const detailGets = await createRecipeAs(profileFixture);
  expect(screen.queryByText(/waiting for review|not published|hidden while/i)).not.toBeInTheDocument();
  expect(await screen.findByRole('region', { name: /comments/i })).toBeInTheDocument();
  expect(detailGets.count).toBe(0);
  expectNoEmoji();
});

test('a leftover pending status shows no banner', async () => {
  signedInAs();
  serveRecipe(recipeFixture({ user: profileFixture, review: review({ status: 'pending' }) }));
  renderApp('/recipes/r-1');
  await screen.findByRole('heading', { name: /bruschetta pork/i });
  expect(screen.queryByText(/waiting for review/i)).not.toBeInTheDocument();
  expect(screen.queryByText(REJECTED_BANNER)).not.toBeInTheDocument();
  expect(screen.queryByText(HIDDEN_BANNER)).not.toBeInTheDocument();
});

test('rejectedBannerForAuthor', async () => {
  signedInAs();
  serveRecipe(recipeFixture({ user: profileFixture, review: review({ status: 'rejected', rejectionReason: 'Photos are blurry.' }) }));
  renderApp('/recipes/r-1');

  expect(await screen.findByText(REJECTED_BANNER)).toBeInTheDocument();
  expect(screen.getByText('Photos are blurry.')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Edit recipe' })).toHaveAttribute('href', '/recipes/r-1/edit');
  expect(screen.queryByRole('region', { name: /comments/i })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /rate \d stars/i })).not.toBeInTheDocument();
  expectNoEmoji();
});

test('hiddenBannerForAuthorRemovesFeedbackControls', async () => {
  signedInAs();
  serveRecipe(recipeFixture({ user: profileFixture, likeCount: 2, review: review({ hidden: true }) }));
  renderApp('/recipes/r-1');

  expect(await screen.findByText(HIDDEN_BANNER)).toBeInTheDocument();
  expect(screen.queryByRole('region', { name: /comments/i })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /rate \d stars/i })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /like/i })).not.toBeInTheDocument();
  expectNoEmoji();
});

test('hidden wins over rejected in the banner', async () => {
  signedInAs();
  serveRecipe(recipeFixture({ user: profileFixture, review: review({ status: 'rejected', hidden: true, rejectionReason: 'No.' }) }));
  renderApp('/recipes/r-1');
  expect(await screen.findByText(HIDDEN_BANNER)).toBeInTheDocument();
  expect(screen.queryByText(REJECTED_BANNER)).not.toBeInTheDocument();
});

test('approved recipe shows no banner and keeps comments and rating', async () => {
  signedInAs();
  serveRecipe(recipeFixture({ user: profileFixture, review: review() }));
  renderApp('/recipes/r-1');

  expect(await screen.findByRole('region', { name: /comments/i })).toBeInTheDocument();
  expect(await screen.findByRole('button', { name: /rate 3 stars/i })).toBeInTheDocument();
  expect(screen.queryByText(/waiting for review/i)).not.toBeInTheDocument();
  expect(screen.queryByText(REJECTED_BANNER)).not.toBeInTheDocument();
});

test('edit form no longer warns about review', async () => {
  signedInAs();
  serveRecipe(recipeFixture({ user: profileFixture, review: review({ status: 'rejected', rejectionReason: 'No.' }) }));
  renderApp('/recipes/r-1/edit');
  await screen.findByRole('button', { name: /^save recipe$/i });
  expect(screen.queryByText(/back for review/i)).not.toBeInTheDocument();
});

test('myRecipesBadges', async () => {
  signedInAs();
  server.use(
    http.get(`${BASE}/api/users/u-1/recipes`, () =>
      HttpResponse.json(
        pageOf([
          recipeFixture({ id: 'a', title: 'Approved Soup', user: profileFixture, review: review() }),
          recipeFixture({ id: 'p', title: 'Hidden Pie', user: profileFixture, review: review({ hidden: true }) }),
          recipeFixture({ id: 'x', title: 'Rejected Roast', user: profileFixture, review: review({ status: 'rejected', rejectionReason: 'No.' }) }),
        ]),
      ),
    ),
    http.get(`${BASE}/api/users/me/saved/ids`, () => HttpResponse.json([])),
  );
  renderApp('/me');

  const card = async (title: string) =>
    (await screen.findByRole('heading', { name: title })).closest('a') as HTMLElement;
  expect(within(await card('Hidden Pie')).getByText('Hidden')).toBeInTheDocument();
  expect(within(await card('Rejected Roast')).getByText('Rejected')).toBeInTheDocument();
  expect(within(await card('Approved Soup')).queryByText(/hidden|rejected|pending/i)).not.toBeInTheDocument();
  expectNoEmoji();
});

test('needsAttentionLabel', async () => {
  signedInAs({ ...profileFixture, rejectedCount: 2 });
  server.use(
    http.get(`${BASE}/api/users/u-1/recipes`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/users/me/saved/ids`, () => HttpResponse.json([])),
  );
  renderApp('/me');

  expect(await screen.findByRole('tab', { name: 'My recipes · 2 needs attention' })).toBeInTheDocument();
  expectNoEmoji();
});

test('tab label stays "My recipes" when nothing is rejected', async () => {
  signedInAs();
  server.use(
    http.get(`${BASE}/api/users/u-1/recipes`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/users/me/saved/ids`, () => HttpResponse.json([])),
  );
  renderApp('/me');

  expect(await screen.findByRole('tab', { name: 'My recipes' })).toBeInTheDocument();
});

test('creating a recipe refreshes the profile so the needs-attention count stays current', async () => {
  let rejectedCount = 1;
  server.use(http.get(`${BASE}/api/users/me`, () => HttpResponse.json({ ...profileFixture, rejectedCount })));
  const created = recipeFixture({ id: 'r-new', title: 'My Pie', user: profileFixture });
  server.use(
    http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json([])),
    http.post(`${BASE}/api/recipes`, () => {
      rejectedCount = 0; // the author edited their rejected recipe elsewhere; the count dropped
      return HttpResponse.json(created, { status: 201 });
    }),
    http.get(`${BASE}/api/users/u-1/recipes`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/users/me/saved/ids`, () => HttpResponse.json([])),
  );
  serveRecipe(created);
  renderApp('/recipes/new');
  await userEvent.type(await screen.findByLabelText(/title/i), 'My Pie');
  await userEvent.type(screen.getByLabelText('Ingredient 1'), 'apples');
  await userEvent.type(screen.getByLabelText('Ingredient 1 amount and notes'), '3 tart apples');
  await userEvent.type(screen.getByLabelText('Servings'), '2');
  await userEvent.click(screen.getByRole('button', { name: /^save recipe$/i }));
  await screen.findByRole('heading', { name: /my pie/i });

  await userEvent.click(screen.getByRole('button', { name: 'Jane' }));
  await userEvent.click(screen.getByRole('link', { name: 'My profile' }));
  expect(await screen.findByRole('tab', { name: 'My recipes' })).toBeInTheDocument();
  expect(screen.queryByText(/needs attention/i)).not.toBeInTheDocument();
});
