import { http, HttpResponse } from 'msw';
import { act, screen } from '@testing-library/react';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { profileFixture, pageOf } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

const mocks = vi.hoisted(() => ({
  fire: undefined as undefined | ((event: string, session: unknown) => void),
}));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({ data: { session: null } }),
      onAuthStateChange: (cb: (event: string, session: unknown) => void) => {
        mocks.fire = cb;
        return { data: { subscription: { unsubscribe() {} } } };
      },
      signOut: async () => {},
    },
  },
}));

test('an auth event re-resolves the profile, and duplicate events fetch it only once', async () => {
  let meCalls = 0;
  server.use(
    http.get(`${BASE}/api/users/me`, () => {
      meCalls += 1;
      return HttpResponse.json(profileFixture);
    }),
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
  );
  renderApp('/');
  expect(await screen.findByRole('link', { name: /sign in/i })).toBeInTheDocument();

  const session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  await act(async () => { mocks.fire?.('SIGNED_IN', session); });
  expect(await screen.findByRole('button', { name: /jane/i })).toBeInTheDocument();

  await act(async () => { mocks.fire?.('TOKEN_REFRESHED', session); }); // same user — must not refetch
  expect(meCalls).toBe(1);
});
