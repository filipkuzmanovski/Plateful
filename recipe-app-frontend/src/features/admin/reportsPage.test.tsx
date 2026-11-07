import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { newTestQueryClient, renderApp } from '@/test/utils';
import { pageOf, profileFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';

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

const recipeItem = {
  kind: 'recipe',
  id: 'r-1',
  recipeId: 'r-1',
  text: 'Lemon Cake',
  authorName: 'Sam Baker',
  postedAt: '2026-11-01T10:00:00Z',
  openCount: 3,
  hidden: true,
  reports: [
    { reason: 'spam', note: 'Looks like an advert', createdAt: '2026-11-02T10:00:00Z' },
    { reason: 'not_food', note: null, createdAt: '2026-11-03T10:00:00Z' },
    { reason: 'other', note: null, createdAt: '2026-11-04T10:00:00Z' },
  ],
};
const commentItem = {
  kind: 'comment',
  id: 'c-9',
  recipeId: 'r-7',
  text: 'This comment is rude and long enough to be read in full.',
  authorName: null,
  postedAt: '2026-11-01T10:00:00Z',
  openCount: 1,
  hidden: false,
  reports: [{ reason: 'offensive', note: 'Insulting', createdAt: '2026-11-02T10:00:00Z' }],
};

function serve(items: unknown[], count = items.length) {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json({ ...profileFixture, isAdmin: true })),
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/admin/reports/count`, () => HttpResponse.json({ count })),
    http.get(`${BASE}/api/admin/reports`, () => HttpResponse.json(pageOf(items))),
  );
}

test('entriesShowKindTextAuthorCountHiddenAndReasons', async () => {
  serve([recipeItem, commentItem]);
  renderApp('/admin/reports');

  const recipe = (await screen.findByRole('heading', { name: 'Lemon Cake' })).closest('article')!;
  expect(within(recipe).getByText('Recipe')).toBeInTheDocument();
  expect(within(recipe).getByText(/Sam Baker/)).toBeInTheDocument();
  expect(within(recipe).getByText('Reported by 3 people')).toBeInTheDocument();
  expect(within(recipe).getByText('Hidden')).toBeInTheDocument();
  expect(within(recipe).getByText('Spam')).toBeInTheDocument();
  expect(within(recipe).getByText('Looks like an advert')).toBeInTheDocument();
  expect(within(recipe).getByText('Not food related')).toBeInTheDocument();
  expect(within(recipe).getByText('Other')).toBeInTheDocument();

  const comment = screen.getByText(commentItem.text).closest('article')!;
  expect(within(comment).getByText('Comment')).toBeInTheDocument();
  expect(within(comment).getByText(/Imported recipe/)).toBeInTheDocument();
  expect(within(comment).getByText('Reported by 1 person')).toBeInTheDocument();
  expect(within(comment).queryByText('Hidden')).not.toBeInTheDocument();
  expect(within(comment).getByText('Offensive or hateful')).toBeInTheDocument();
  expect(within(comment).getByText('Insulting')).toBeInTheDocument();
  expectNoEmoji();
});

test('openLinksToTheRecipe', async () => {
  serve([commentItem]);
  renderApp('/admin/reports');
  expect(await screen.findByRole('link', { name: 'Open' })).toHaveAttribute('href', '/recipes/r-7');
});

test('dismissConfirmsPostsAndRemovesTheEntry', async () => {
  serve([recipeItem, commentItem]);
  let dismissed = false;
  server.use(
    http.post(`${BASE}/api/admin/reports/recipes/r-1/dismiss`, () => {
      dismissed = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  const queryClient = newTestQueryClient();
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
  renderApp('/admin/reports', queryClient);
  const article = (await screen.findByRole('heading', { name: 'Lemon Cake' })).closest('article')!;
  await userEvent.click(within(article).getByRole('button', { name: 'Dismiss' }));
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).getByText('Dismiss these reports?')).toBeInTheDocument();
  expect(within(dialog).getByText('The item will show again.')).toBeInTheDocument();
  expect(dismissed).toBe(false);
  serve([commentItem]);
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dismiss' }));
  await waitFor(() => expect(screen.queryByRole('heading', { name: 'Lemon Cake' })).not.toBeInTheDocument());
  expect(dismissed).toBe(true);
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['recipe'] });
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['recipes'] });
  expect(screen.getByText(commentItem.text)).toBeInTheDocument();
});

test('cancellingTheDismissConfirmKeepsTheEntry', async () => {
  serve([commentItem]);
  renderApp('/admin/reports');
  await userEvent.click(await screen.findByRole('button', { name: 'Dismiss' }));
  await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByText(commentItem.text)).toBeInTheDocument();
});

test('removeAsksForAReasonAndRemovesTheEntry', async () => {
  serve([commentItem]);
  let body: unknown;
  server.use(
    http.post(`${BASE}/api/admin/comments/c-9/remove`, async ({ request }) => {
      body = await request.json();
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderApp('/admin/reports');
  await userEvent.click(await screen.findByRole('button', { name: 'Remove' }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.type(within(dialog).getByLabelText('Reason'), 'Abusive');
  serve([]);
  await userEvent.click(within(dialog).getByRole('button', { name: 'Remove comment' }));
  await waitFor(() => expect(screen.queryByText(commentItem.text)).not.toBeInTheDocument());
  expect(body).toEqual({ reason: 'Abusive' });
  expect(await screen.findByText('No open reports.')).toBeInTheDocument();
});

test.each([404, 409])('a %i on dismiss says it was already handled and refetches', async (status) => {
  serve([commentItem]);
  let lists = 0;
  server.use(
    http.get(`${BASE}/api/admin/reports`, () => {
      lists++;
      return HttpResponse.json(pageOf(lists === 1 ? [commentItem] : []));
    }),
    http.post(`${BASE}/api/admin/reports/comments/c-9/dismiss`, () =>
      HttpResponse.json({ status, code: status === 409 ? 'already_handled' : 'not_found', detail: 'gone' }, { status }),
    ),
  );
  renderApp('/admin/reports');
  await userEvent.click(await screen.findByRole('button', { name: 'Dismiss' }));
  await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Dismiss' }));
  expect(await screen.findByText('This was already handled.')).toBeInTheDocument();
  await waitFor(() => expect(lists).toBe(2));
  await waitFor(() => expect(screen.queryByText(commentItem.text)).not.toBeInTheDocument());
});

test('emptyStateReadsNoOpenReports', async () => {
  serve([]);
  renderApp('/admin/reports');
  expect(await screen.findByText('No open reports.')).toBeInTheDocument();
});

test('theHeaderShowsReportsWithTheCount', async () => {
  serve([recipeItem], 4);
  renderApp('/admin/reports');
  const link = await screen.findByRole('link', { name: 'Reports, 4 open' });
  expect(link).toHaveAttribute('href', '/admin/reports');
  expect(within(link).getByText('4')).toBeInTheDocument();
});
