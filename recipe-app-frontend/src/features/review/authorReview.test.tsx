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

const SUBMITTED_TOAST = "Submitted for review. We'll email you when it's approved.";
const EDIT_NOTICE =
  "Saving changes sends this recipe back for review. It will be hidden from others until it's approved.";

function review(overrides: Partial<RecipeReview> = {}): RecipeReview {
  return {
    status: 'pending', rejectionReason: null, everApproved: false, revision: 1,
    submittedAt: '2026-10-18T10:00:00Z', ...overrides,
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

async function createRecipeAs(profile: MyProfile) {
  signedInAs(profile);
  const created = recipeFixture({ id: 'r-new', title: 'My Pie', user: profile });
  server.use(
    http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json([])),
    http.post(`${BASE}/api/recipes`, () => HttpResponse.json(created, { status: 201 })),
  );
  serveRecipe(created);
  renderApp('/recipes/new');
  await userEvent.type(await screen.findByLabelText(/title/i), 'My Pie');
  await userEvent.type(screen.getByLabelText(/ingredient 1 name/i), 'apples');
  await userEvent.type(screen.getByLabelText(/ingredient 1 text/i), '3 tart apples');
  await userEvent.click(screen.getByRole('button', { name: /^save recipe$/i }));
  expect(await screen.findByRole('heading', { name: /my pie/i })).toBeInTheDocument();
}

test('createShowsSubmittedToast', async () => {
  await createRecipeAs(profileFixture);
  expect(await screen.findByText(SUBMITTED_TOAST)).toBeInTheDocument();
  expectNoEmoji();
});

test('adminCreateHasNoReviewToast', async () => {
  await createRecipeAs({ ...profileFixture, isAdmin: true });
  expect(screen.queryByText(SUBMITTED_TOAST)).not.toBeInTheDocument();
  expectNoEmoji();
});

test('pendingBannerForAuthor', async () => {
  signedInAs();
  serveRecipe(recipeFixture({ user: profileFixture, review: review({ status: 'pending' }) }));
  renderApp('/recipes/r-1');

  expect(await screen.findByText('Waiting for review. Only you can see this recipe.')).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: /bruschetta pork/i })).toBeInTheDocument();
  expect(screen.queryByRole('region', { name: /comments/i })).not.toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: /^comments/i })).not.toBeInTheDocument();
  expect(screen.queryByText(/your rating/i)).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /rate \d stars/i })).not.toBeInTheDocument();
  expectNoEmoji();
});

test('rejectedBannerShowsReasonAndResubmitLink', async () => {
  signedInAs();
  serveRecipe(
    recipeFixture({
      user: profileFixture,
      review: review({ status: 'rejected', rejectionReason: 'Photos are blurry.' }),
    }),
  );
  renderApp('/recipes/r-1');

  expect(await screen.findByText("This recipe wasn't approved.")).toBeInTheDocument();
  expect(screen.getByText('Photos are blurry.')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Edit and resubmit' })).toHaveAttribute('href', '/recipes/r-1/edit');
  expect(screen.queryByRole('region', { name: /comments/i })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /rate \d stars/i })).not.toBeInTheDocument();
  expectNoEmoji();
});

test('approved recipe shows no banner and keeps comments and rating', async () => {
  signedInAs();
  serveRecipe(recipeFixture({ user: profileFixture, review: review({ status: 'approved', everApproved: true }) }));
  renderApp('/recipes/r-1');

  expect(await screen.findByRole('region', { name: /comments/i })).toBeInTheDocument();
  expect(await screen.findByRole('button', { name: /rate 3 stars/i })).toBeInTheDocument();
  expect(screen.queryByText(/waiting for review/i)).not.toBeInTheDocument();
  expect(screen.queryByText("This recipe wasn't approved.")).not.toBeInTheDocument();
});

test('editApprovedShowsNotice', async () => {
  signedInAs();
  serveRecipe(recipeFixture({ user: profileFixture, review: review({ status: 'approved', everApproved: true }) }));
  renderApp('/recipes/r-1/edit');

  expect(await screen.findByText(EDIT_NOTICE)).toBeInTheDocument();
  expectNoEmoji();
});

test('edit notice also shows for a rejected recipe but not a pending one', async () => {
  signedInAs();
  serveRecipe(recipeFixture({ user: profileFixture, review: review({ status: 'rejected', rejectionReason: 'No.' }) }));
  const { unmount } = renderApp('/recipes/r-1/edit');
  expect(await screen.findByText(EDIT_NOTICE)).toBeInTheDocument();
  unmount();

  serveRecipe(recipeFixture({ user: profileFixture, review: review({ status: 'pending' }) }));
  renderApp('/recipes/r-1/edit');
  await screen.findByRole('button', { name: /^save recipe$/i });
  expect(screen.queryByText(EDIT_NOTICE)).not.toBeInTheDocument();
});

test('adminEditHasNoNotice', async () => {
  signedInAs({ ...profileFixture, isAdmin: true });
  serveRecipe(recipeFixture({ user: profileFixture, review: review({ status: 'approved', everApproved: true }) }));
  renderApp('/recipes/r-1/edit');

  await screen.findByRole('button', { name: /^save recipe$/i });
  expect(screen.queryByText(EDIT_NOTICE)).not.toBeInTheDocument();
  expectNoEmoji();
});

test('myRecipesBadges', async () => {
  signedInAs();
  server.use(
    http.get(`${BASE}/api/users/u-1/recipes`, () =>
      HttpResponse.json(
        pageOf([
          recipeFixture({ id: 'a', title: 'Approved Soup', user: profileFixture, review: review({ status: 'approved', everApproved: true }) }),
          recipeFixture({ id: 'p', title: 'Pending Pie', user: profileFixture, review: review({ status: 'pending' }) }),
          recipeFixture({ id: 'x', title: 'Rejected Roast', user: profileFixture, review: review({ status: 'rejected', rejectionReason: 'No.' }) }),
        ]),
      ),
    ),
    http.get(`${BASE}/api/users/me/saved/ids`, () => HttpResponse.json([])),
  );
  renderApp('/me');

  const card = async (title: string) =>
    (await screen.findByRole('heading', { name: title })).closest('a') as HTMLElement;
  expect(within(await card('Pending Pie')).getByText('Pending review')).toBeInTheDocument();
  expect(within(await card('Rejected Roast')).getByText('Rejected')).toBeInTheDocument();
  expect(within(await card('Approved Soup')).queryByText(/pending review|rejected/i)).not.toBeInTheDocument();
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
  await userEvent.type(screen.getByLabelText(/ingredient 1 name/i), 'apples');
  await userEvent.type(screen.getByLabelText(/ingredient 1 text/i), '3 tart apples');
  await userEvent.click(screen.getByRole('button', { name: /^save recipe$/i }));
  await screen.findByRole('heading', { name: /my pie/i });

  await userEvent.click(screen.getByRole('button', { name: 'Jane' }));
  await userEvent.click(screen.getByRole('link', { name: 'My profile' }));
  expect(await screen.findByRole('tab', { name: 'My recipes' })).toBeInTheDocument();
  expect(screen.queryByText(/needs attention/i)).not.toBeInTheDocument();
});
