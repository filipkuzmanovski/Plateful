import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, profileFixture, commentFixture, pageOf } from '@/test/fixtures';
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
  const likeBtn = await screen.findByRole('button', { name: /^like comment$/i });
  expectNoEmoji();
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

test('a failed reaction shows the server error in a toast', async () => {
  stubPage();
  server.use(
    http.put(`${BASE}/api/comments/c-1/reactions/me`, () =>
      HttpResponse.json({ status: 404, detail: 'Comment with id c-1 not found' }, { status: 404 }),
    ),
  );
  renderApp('/recipes/r-1');
  await userEvent.click(await screen.findByRole('button', { name: /^like comment$/i }));
  expect(await screen.findByText('Comment with id c-1 not found')).toBeInTheDocument();
});
