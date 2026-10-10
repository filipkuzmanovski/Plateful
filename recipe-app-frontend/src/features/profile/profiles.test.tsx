import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, profileFixture, pageOf } from '@/test/fixtures';
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
  expectNoEmoji();
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
