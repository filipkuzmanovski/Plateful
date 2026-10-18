import { http, HttpResponse } from 'msw';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, focusManager } from '@tanstack/react-query';
import { server } from '@/test/server';
import { newTestQueryClient, renderApp } from '@/test/utils';
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

const admin: MyProfile = { ...profileFixture, isAdmin: true };
const author = { id: 'u-9', firstName: 'Sam', lastName: 'Baker' };

function review(overrides: Partial<RecipeReview> = {}): RecipeReview {
  return {
    status: 'pending', rejectionReason: null, everApproved: false, revision: 4,
    submittedAt: '2026-10-18T10:00:00Z', ...overrides,
  };
}

function pending(id: string, title: string, overrides: Partial<RecipeReview> = {}): RecipeDisplay {
  return recipeFixture({ id, title, user: author, review: review(overrides) });
}

const problem = (status: number, code: string, extra: Record<string, unknown> = {}) =>
  HttpResponse.json({ status, detail: `problem ${code}`, code, ...extra }, { status });

function signedInAs(profile: MyProfile) {
  server.use(http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profile)));
}

/** Serves the admin API: the header count, the queue, and the recipes by id. */
function serveAdmin(opts: { queue?: RecipeDisplay[]; recipes?: RecipeDisplay[]; count?: number } = {}) {
  const { queue = [], recipes = [] } = opts;
  const count = opts.count ?? queue.length;
  server.use(
    http.get(`${BASE}/api/admin/recipes/pending/count`, () => HttpResponse.json({ count })),
    http.get(`${BASE}/api/admin/recipes/pending`, () => HttpResponse.json(pageOf(queue))),
    ...recipes.map((r) => http.get(`${BASE}/api/admin/recipes/${r.id}`, () => HttpResponse.json(r))),
  );
}

/** Opens the review page for `current`, with `next` waiting in the queue afterwards (if any). */
async function openReview(current: RecipeDisplay, next: RecipeDisplay[] = []) {
  signedInAs(admin);
  serveAdmin({ queue: next, recipes: [current, ...next] });
  renderApp(`/admin/review/${current.id}`);
  expect(await screen.findByRole('heading', { name: current.title })).toBeInTheDocument();
}

test('headerLinkOnlyForAdmins', async () => {
  signedInAs(admin);
  serveAdmin({ queue: [], count: 3 });
  server.use(http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))));
  renderApp('/');

  const link = await screen.findByRole('link', { name: 'Review queue, 3 pending' });
  expect(link).toHaveAttribute('href', '/admin/review');
  expect(within(link).getByText('Review queue')).toBeInTheDocument();
  expect(within(link).getByText('3')).toBeInTheDocument();
  expectNoEmoji();
});

test('headerLinkHasNoBadgeWithoutPendingRecipes', async () => {
  signedInAs(admin);
  serveAdmin({ queue: [], count: 0 });
  server.use(http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))));
  renderApp('/');

  const link = await screen.findByRole('link', { name: 'Review queue' });
  await waitFor(() => expect(link).toHaveTextContent(/^Review queue$/));
});

test('headerLinkAbsentForNonAdmins', async () => {
  let countAsked = false;
  signedInAs(profileFixture);
  server.use(
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/admin/recipes/pending/count`, () => {
      countAsked = true;
      return HttpResponse.json({ count: 3 });
    }),
  );
  renderApp('/');

  expect(await screen.findByRole('button', { name: /jane/i })).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /review queue/i })).not.toBeInTheDocument();
  expect(countAsked).toBe(false);
});

test('nonAdminRouteIsNotFound', async () => {
  signedInAs(profileFixture);
  renderApp('/admin/review');
  expect(await screen.findByText('Page not found.')).toBeInTheDocument();
});

test('nonAdminReviewRouteIsNotFound', async () => {
  signedInAs(profileFixture);
  renderApp('/admin/review/r-1');
  expect(await screen.findByText('Page not found.')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
});

test('queueListsOldestFirstWithReReviewTag', async () => {
  signedInAs(admin);
  serveAdmin({
    queue: [
      pending('r-1', 'Oldest Soup'),
      pending('r-2', 'Edited Stew', { everApproved: true }),
    ],
  });
  renderApp('/admin/review');

  const rows = await within(screen.getByRole('main')).findAllByRole('listitem');
  expect(rows).toHaveLength(2);
  expect(within(rows[0]).getByRole('link', { name: /oldest soup/i })).toHaveAttribute('href', '/admin/review/r-1');
  expect(within(rows[0]).getByText(/Sam Baker/)).toBeInTheDocument();
  expect(within(rows[0]).queryByText('Re-review')).not.toBeInTheDocument();
  expect(within(rows[1]).getByRole('link', { name: /edited stew/i })).toHaveAttribute('href', '/admin/review/r-2');
  expect(within(rows[1]).getByText('Re-review')).toBeInTheDocument();
  expect(rows[0].querySelector('time')).toHaveAttribute('datetime', '2026-10-18T10:00:00Z');
  expectNoEmoji();
});

test('queueEmptyState', async () => {
  signedInAs(admin);
  serveAdmin({ queue: [] });
  renderApp('/admin/review');
  expect(await screen.findByText('Nothing to review right now.')).toBeInTheDocument();
  expectNoEmoji();
});

test('reviewPageShowsTheRecipeWithoutSocialParts', async () => {
  await openReview(pending('r-1', 'Lemon Tart'));

  expect(screen.getByText('8 oz penne pasta')).toBeInTheDocument();
  expect(screen.getByText('Boil the pasta.')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Approve' })).toBeEnabled();
  expect(screen.getByRole('button', { name: 'Reject' })).toBeEnabled();
  expect(screen.queryByRole('region', { name: /comments/i })).not.toBeInTheDocument();
  expect(screen.queryByText(/rate this recipe|your rating/i)).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /save/i })).not.toBeInTheDocument();
  expect(screen.queryByText('Waiting for review. Only you can see this recipe.')).not.toBeInTheDocument();
  expectNoEmoji();
});

test('approveMovesToNext', async () => {
  let body: unknown;
  await openReview(pending('r-1', 'Lemon Tart'), [pending('r-2', 'Plum Cake')]);
  server.use(
    http.post(`${BASE}/api/admin/recipes/r-1/approve`, async ({ request }) => {
      body = await request.json();
      return new HttpResponse(null, { status: 204 });
    }),
  );

  await userEvent.click(screen.getByRole('button', { name: 'Approve' }));

  expect(await screen.findByText('Recipe approved')).toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: 'Plum Cake' })).toBeInTheDocument();
  expect(body).toEqual({ revision: 4 });
  expectNoEmoji();
});

test('approveLastGoesToQueue', async () => {
  await openReview(pending('r-1', 'Lemon Tart'));
  server.use(http.post(`${BASE}/api/admin/recipes/r-1/approve`, () => new HttpResponse(null, { status: 204 })));

  await userEvent.click(screen.getByRole('button', { name: 'Approve' }));

  expect(await screen.findByText('Nothing to review right now.')).toBeInTheDocument();
  expect(screen.getByText('Recipe approved')).toBeInTheDocument();
});

test('decisionInvalidatesEveryCacheThatShowsTheRecipe', async () => {
  const queryClient = newTestQueryClient();
  signedInAs(admin);
  serveAdmin({ queue: [], recipes: [pending('r-1', 'Lemon Tart')] });
  server.use(http.post(`${BASE}/api/admin/recipes/r-1/approve`, () => new HttpResponse(null, { status: 204 })));
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
  renderApp('/admin/review/r-1', queryClient);

  await userEvent.click(await screen.findByRole('button', { name: 'Approve' }));
  await screen.findByText('Nothing to review right now.');

  const keys = invalidate.mock.calls.map(([filter]) => filter?.queryKey);
  expect(keys).toEqual(
    expect.arrayContaining([['admin'], ['recipes'], ['recipe', 'r-1'], ['userRecipes'], ['savedRecipes']]),
  );
});

test('rejectRequiresReasonWithCounter', async () => {
  await openReview(pending('r-1', 'Lemon Tart'));

  await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
  const dialog = await screen.findByRole('dialog');
  const submit = within(dialog).getByRole('button', { name: 'Reject recipe' });
  const reason = within(dialog).getByRole('textbox');
  expect(within(dialog).getByRole('button', { name: 'Cancel' })).toBeEnabled();
  expect(submit).toBeDisabled();
  expect(within(dialog).getByText('0/500')).toBeInTheDocument();

  await userEvent.type(reason, '   ');
  expect(submit).toBeDisabled();
  expect(within(dialog).getByText('0/500')).toBeInTheDocument();

  await userEvent.type(reason, 'Needs photos ');
  expect(submit).toBeEnabled();
  expect(within(dialog).getByText('12/500')).toBeInTheDocument();

  await userEvent.clear(reason);
  await userEvent.click(reason);
  await userEvent.paste('x'.repeat(500));
  expect(within(dialog).getByText('500/500')).toBeInTheDocument();
  expect(submit).toBeEnabled();

  await userEvent.paste('x');
  expect(within(dialog).getByText('501/500')).toBeInTheDocument();
  expect(submit).toBeDisabled();
  expectNoEmoji();
});

test('cancelClosesTheRejectDialogWithoutSending', async () => {
  let sent = false;
  await openReview(pending('r-1', 'Lemon Tart'));
  server.use(
    http.post(`${BASE}/api/admin/recipes/r-1/reject`, () => {
      sent = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );

  await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
  await userEvent.type(await screen.findByRole('textbox'), 'Draft reason');
  await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(sent).toBe(false);

  // Reopening starts from an empty reason.
  await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
  expect(await screen.findByRole('textbox')).toHaveValue('');
});

test('rejectSendsReason', async () => {
  let body: unknown;
  await openReview(pending('r-1', 'Lemon Tart'), [pending('r-2', 'Plum Cake')]);
  server.use(
    http.post(`${BASE}/api/admin/recipes/r-1/reject`, async ({ request }) => {
      body = await request.json();
      return new HttpResponse(null, { status: 204 });
    }),
  );

  await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
  await userEvent.type(await screen.findByRole('textbox'), '  Please add a photo.  ');
  await userEvent.click(screen.getByRole('button', { name: 'Reject recipe' }));

  expect(await screen.findByText('Recipe rejected')).toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: 'Plum Cake' })).toBeInTheDocument();
  expect(body).toEqual({ revision: 4, reason: 'Please add a photo.' });
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

test('rejectShowsTheServersReasonError', async () => {
  await openReview(pending('r-1', 'Lemon Tart'));
  server.use(
    http.post(`${BASE}/api/admin/recipes/r-1/reject`, () =>
      HttpResponse.json(
        { status: 400, detail: 'Validation failed', errors: { reason: 'Reason must be 500 characters or fewer' } },
        { status: 400 },
      ),
    ),
  );

  await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
  await userEvent.type(await screen.findByRole('textbox'), 'Too long somehow');
  await userEvent.click(screen.getByRole('button', { name: 'Reject recipe' }));

  const dialog = await screen.findByRole('dialog');
  expect(await within(dialog).findByRole('alert')).toHaveTextContent('Reason must be 500 characters or fewer');
});

test('recipeChangedShowsReload', async () => {
  const bodies: unknown[] = [];
  await openReview(pending('r-1', 'Lemon Tart'));
  server.use(
    http.post(`${BASE}/api/admin/recipes/r-1/approve`, async ({ request }) => {
      bodies.push(await request.json());
      return bodies.length === 1 ? problem(409, 'recipe_changed') : new HttpResponse(null, { status: 204 });
    }),
  );

  await userEvent.click(screen.getByRole('button', { name: 'Approve' }));

  expect(await screen.findByText('This recipe changed. Reload to review the latest version.')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled();

  // Reload fetches the edited recipe, with its new revision.
  serveAdmin({ recipes: [pending('r-1', 'Lemon Tart, revised', { revision: 5 })] });
  await userEvent.click(screen.getByRole('button', { name: 'Reload' }));

  expect(await screen.findByRole('heading', { name: 'Lemon Tart, revised' })).toBeInTheDocument();
  expect(screen.queryByText('This recipe changed. Reload to review the latest version.')).not.toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
  await screen.findByText('Recipe approved');
  expect(bodies).toEqual([{ revision: 4 }, { revision: 5 }]);
  expectNoEmoji();
});

test('notPendingMessage', async () => {
  await openReview(pending('r-1', 'Lemon Tart'));
  server.use(http.post(`${BASE}/api/admin/recipes/r-1/approve`, () => problem(409, 'not_pending')));

  await userEvent.click(screen.getByRole('button', { name: 'Approve' }));

  expect(await screen.findByText('This recipe was already reviewed.')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Back to the queue' })).toHaveAttribute('href', '/admin/review');
  expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
});

test('rejectNotPendingMessage', async () => {
  await openReview(pending('r-1', 'Lemon Tart'));
  server.use(http.post(`${BASE}/api/admin/recipes/r-1/reject`, () => problem(409, 'not_pending')));

  await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
  await userEvent.type(await screen.findByRole('textbox'), 'Nope');
  await userEvent.click(screen.getByRole('button', { name: 'Reject recipe' }));

  expect(await screen.findByText('This recipe was already reviewed.')).toBeInTheDocument();
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
});

test('alreadyReviewedRecipeOffersNoDecision', async () => {
  await openReview(pending('r-1', 'Lemon Tart', { status: 'approved' }));
  expect(screen.getByText('This recipe was already reviewed.')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
});

test('approve404Message', async () => {
  await openReview(pending('r-1', 'Lemon Tart'));
  server.use(
    http.post(`${BASE}/api/admin/recipes/r-1/approve`, () =>
      HttpResponse.json({ status: 404, detail: 'Recipe not found' }, { status: 404 }),
    ),
  );

  await userEvent.click(screen.getByRole('button', { name: 'Approve' }));

  expect(await screen.findByText('This recipe is no longer in the queue.')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Back to the queue' })).toHaveAttribute('href', '/admin/review');
});

test('adminWithoutMfaSeesPanel', async () => {
  signedInAs(admin);
  server.use(
    http.get(`${BASE}/api/admin/recipes/pending/count`, () => problem(403, 'admin_mfa_setup_required')),
    http.get(`${BASE}/api/admin/recipes/pending`, () => problem(403, 'admin_mfa_setup_required')),
  );
  renderApp('/admin/review');

  expect(await screen.findByText('Set up two-factor authentication to use the review queue.')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /two-factor/i })).toHaveAttribute('href', '/me');
  expectNoEmoji();
});

test('adminWithoutMfaSeesPanelOnTheReviewPage', async () => {
  signedInAs(admin);
  server.use(
    http.get(`${BASE}/api/admin/recipes/pending/count`, () => problem(403, 'admin_mfa_setup_required')),
    http.get(`${BASE}/api/admin/recipes/r-1`, () => problem(403, 'admin_mfa_setup_required')),
  );
  renderApp('/admin/review/r-1');

  expect(await screen.findByText('Set up two-factor authentication to use the review queue.')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /two-factor/i })).toHaveAttribute('href', '/me');
});

test('focusDoesNotRefetchReviewedRecipe', async () => {
  // Stale immediately, as it would be after a long time in another tab: a refetch on focus would be allowed.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } });
  let body: unknown;
  signedInAs(admin);
  serveAdmin({ recipes: [pending('r-1', 'Lemon Tart')] });
  renderApp('/admin/review/r-1', queryClient);
  expect(await screen.findByRole('heading', { name: 'Lemon Tart' })).toBeInTheDocument();

  // The author edits while the admin is away.
  let fetches = 0;
  server.use(
    http.get(`${BASE}/api/admin/recipes/r-1`, () => {
      fetches++;
      return HttpResponse.json(pending('r-1', 'Lemon Tart, edited', { revision: 5 }));
    }),
    http.post(`${BASE}/api/admin/recipes/r-1/approve`, async ({ request }) => {
      body = await request.json();
      return new HttpResponse(null, { status: 204 });
    }),
  );
  act(() => {
    focusManager.setFocused(false);
    focusManager.setFocused(true);
  });
  window.dispatchEvent(new Event('focus'));
  document.dispatchEvent(new Event('visibilitychange'));
  await new Promise((resolve) => setTimeout(resolve, 300));

  expect(fetches).toBe(0);
  expect(screen.getByRole('heading', { name: 'Lemon Tart' })).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
  await screen.findByText('Recipe approved');
  expect(body).toEqual({ revision: 4 });
});

test('failedReloadKeepsThePage', async () => {
  await openReview(pending('r-1', 'Lemon Tart'));
  server.use(http.post(`${BASE}/api/admin/recipes/r-1/approve`, () => problem(409, 'recipe_changed')));
  await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
  await screen.findByText('This recipe changed. Reload to review the latest version.');

  server.use(http.get(`${BASE}/api/admin/recipes/r-1`, () => problem(500, 'boom')));
  await userEvent.click(screen.getByRole('button', { name: 'Reload' }));

  expect(await screen.findByText("Couldn't reload this recipe.")).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: 'Lemon Tart' })).toBeInTheDocument();
  expect(screen.queryByText("Couldn't load this recipe.")).not.toBeInTheDocument();
  expect(screen.getByText('This recipe changed. Reload to review the latest version.')).toBeInTheDocument();
});

test('rejectDialogIgnoresEscapeWhileSending', async () => {
  let release: () => void = () => {};
  const gate = new Promise<void>((resolve) => { release = resolve; });
  await openReview(pending('r-1', 'Lemon Tart'));
  server.use(
    http.post(`${BASE}/api/admin/recipes/r-1/reject`, async () => {
      await gate;
      return new HttpResponse(null, { status: 204 });
    }),
  );

  await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
  await userEvent.type(await screen.findByRole('textbox'), 'Needs work');
  await userEvent.click(screen.getByRole('button', { name: 'Reject recipe' }));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Reject recipe' })).toBeDisabled());

  await userEvent.keyboard('{Escape}');
  expect(screen.getByRole('dialog')).toBeInTheDocument();
  release();
  expect(await screen.findByText('Recipe rejected')).toBeInTheDocument();
});

test('serverReasonErrorClearsWhenTheReasonChanges', async () => {
  await openReview(pending('r-1', 'Lemon Tart'));
  server.use(
    http.post(`${BASE}/api/admin/recipes/r-1/reject`, () =>
      HttpResponse.json({ status: 400, detail: 'Validation failed', errors: { reason: 'Bad reason' } }, { status: 400 }),
    ),
  );

  await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
  const reason = await screen.findByRole('textbox');
  await userEvent.type(reason, 'First');
  await userEvent.click(screen.getByRole('button', { name: 'Reject recipe' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Bad reason');

  await userEvent.type(reason, ' more');
  expect(screen.queryByText('Bad reason')).not.toBeInTheDocument();
});

test('reviewPageLinksBackToTheQueue', async () => {
  await openReview(pending('r-1', 'Lemon Tart'));
  expect(screen.getByRole('link', { name: 'Back to the queue' })).toHaveAttribute('href', '/admin/review');
});

test('reopeningARecipeShowsTheLatestRevision', async () => {
  await openReview(pending('r-1', 'Lemon Tart'));

  // The author edits while the admin is back on the queue.
  const edited = pending('r-1', 'Lemon Tart, edited', { revision: 5 });
  serveAdmin({ queue: [edited], recipes: [edited] });
  let body: unknown;
  server.use(
    http.post(`${BASE}/api/admin/recipes/r-1/approve`, async ({ request }) => {
      body = await request.json();
      return new HttpResponse(null, { status: 204 });
    }),
  );
  await userEvent.click(screen.getByRole('link', { name: 'Back to the queue' }));
  await userEvent.click(await screen.findByRole('link', { name: /lemon tart, edited/i }));

  expect(await screen.findByRole('heading', { name: 'Lemon Tart, edited' })).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
  await screen.findByText('Recipe approved');
  expect(body).toEqual({ revision: 5 });
});
