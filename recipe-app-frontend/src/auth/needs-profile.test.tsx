import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { pageOf } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

const mocks = vi.hoisted(() => ({
  session: null as unknown,
  signUp: undefined as undefined | (() => Promise<unknown>),
}));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({ data: { session: mocks.session } }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
      signUp: () => mocks.signUp!(),
    },
  },
}));

beforeEach(() => {
  mocks.session = null;
  mocks.signUp = async () => ({ data: { session: null, user: { id: 'u-9' } }, error: null });
  server.use(http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))));
});

test('a logged-in user without a profile is sent to complete-profile from a public page', async () => {
  mocks.session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  server.use(
    http.get(`${BASE}/api/users/me`, () =>
      HttpResponse.json({ status: 404, detail: 'User not found' }, { status: 404 }),
    ),
  );
  renderApp('/');
  expect(await screen.findByRole('heading', { name: /complete your profile/i })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /sign out/i })).toBeInTheDocument();
});

test('signup with email confirmation on shows a check-your-email message', async () => {
  renderApp('/signup');
  await userEvent.type(await screen.findByLabelText(/email/i), 'new@example.com');
  await userEvent.type(screen.getByLabelText(/password/i), 'secret123');
  await userEvent.click(screen.getByRole('button', { name: /sign up/i }));
  expect(await screen.findByText(/check your email/i)).toBeInTheDocument();
});
