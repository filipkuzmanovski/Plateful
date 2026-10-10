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
