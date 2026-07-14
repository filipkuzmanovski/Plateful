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
