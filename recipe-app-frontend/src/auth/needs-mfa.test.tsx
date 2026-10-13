import { http, HttpResponse } from 'msw';
import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { profileFixture, pageOf } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

const b64url = (o: unknown) =>
  btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const jwt = (aal: string) => `${b64url({ alg: 'HS256' })}.${b64url({ aal })}.sig`;
const sessionFor = (id: string, aal: string) => ({
  access_token: jwt(aal),
  user: { id, email: `${id}@example.com` },
});

const mocks = vi.hoisted(() => ({
  session: null as unknown,
  fire: undefined as undefined | ((event: string, session: unknown) => void),
  signOut: undefined as undefined | (() => Promise<unknown>),
  getAal: undefined as undefined | (() => Promise<unknown>),
}));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({ data: { session: mocks.session } }),
      onAuthStateChange: (cb: (event: string, session: unknown) => void) => {
        mocks.fire = cb;
        return { data: { subscription: { unsubscribe() {} } } };
      },
      signOut: () => mocks.signOut!(),
      mfa: { getAuthenticatorAssuranceLevel: () => mocks.getAal!() },
    },
  },
}));

let meCalls = 0;

beforeEach(() => {
  meCalls = 0;
  mocks.fire = undefined;
  mocks.session = sessionFor('u-1', 'aal1');
  mocks.signOut = vi.fn(async () => ({}));
  mocks.getAal = async () => ({ data: { currentLevel: 'aal1', nextLevel: 'aal2' }, error: null });
  server.use(
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/users/me`, () => {
      meCalls += 1;
      return HttpResponse.json(profileFixture);
    }),
  );
});

test('protectedRouteRedirectsToVerify', async () => {
  renderApp('/me');
  expect(await screen.findByRole('heading', { name: /two-factor verification/i })).toBeInTheDocument();
  expect(meCalls).toBe(0);
});

test('publicPageStillRenders', async () => {
  renderApp('/');
  expect(await screen.findByRole('heading', { name: /cook something/i })).toBeInTheDocument();
  expect(meCalls).toBe(0);
});

test('upgradeToAal2LoadsProfile', async () => {
  renderApp('/');
  expect(await screen.findByRole('link', { name: /finish signing in/i })).toBeInTheDocument();
  expect(meCalls).toBe(0);

  await act(async () => { mocks.fire?.('TOKEN_REFRESHED', sessionFor('u-1', 'aal2')); });
  expect(await screen.findByRole('button', { name: /jane/i })).toBeInTheDocument();
  expect(meCalls).toBe(1);
  expect(screen.queryByRole('link', { name: /finish signing in/i })).not.toBeInTheDocument();

  await act(async () => { mocks.fire?.('TOKEN_REFRESHED', sessionFor('u-1', 'aal2')); });
  expect(meCalls).toBe(1); // a further aal2 refresh must not refetch
  expect(screen.getByRole('button', { name: /jane/i })).toBeInTheDocument();
});

test('headerOffersSignOutWhileNeedsMfa', async () => {
  renderApp('/');
  const signOut = await screen.findByRole('button', { name: /sign out/i });
  expect(screen.getByRole('link', { name: /finish signing in/i })).toHaveAttribute('href', '/verify-2fa');
  await userEvent.click(signOut);
  expect(mocks.signOut).toHaveBeenCalled();
});

test('a failing AAL lookup falls back to the normal profile path', async () => {
  mocks.getAal = () => Promise.reject(new Error('network'));
  renderApp('/');
  expect(await screen.findByRole('button', { name: /jane/i })).toBeInTheDocument();
  expect(meCalls).toBe(1);
});

test('an mfa_required answer from /me lands on needs-mfa, not anonymous', async () => {
  // e.g. 2FA was enabled on another device after this browser signed in: the AAL lookup sees no factor.
  mocks.getAal = async () => ({ data: { currentLevel: 'aal1', nextLevel: 'aal1' }, error: null });
  server.use(
    http.get(`${BASE}/api/users/me`, () => {
      meCalls += 1;
      return HttpResponse.json(
        { status: 401, detail: 'Two-factor verification required', code: 'mfa_required' },
        { status: 401 },
      );
    }),
  );
  renderApp('/');
  expect(await screen.findByRole('link', { name: /finish signing in/i })).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /^sign in$/i })).not.toBeInTheDocument();
  expect(meCalls).toBe(1);

  await act(async () => { mocks.fire?.('TOKEN_REFRESHED', sessionFor('u-1', 'aal1')); });
  expect(meCalls).toBe(1); // duplicate event must not retry in a loop
});

test('an aal1 to aal2 upgrade of a ready user keeps the page mounted while it refetches', async () => {
  mocks.getAal = async () => ({ data: { currentLevel: 'aal1', nextLevel: 'aal1' }, error: null }); // no factor yet
  renderApp('/me');
  const heading = await screen.findByRole('heading', { name: /^profile$/i });
  expect(meCalls).toBe(1);

  let release!: () => void;
  const gate = new Promise<void>((r) => { release = r; });
  server.use(
    http.get(`${BASE}/api/users/me`, async () => {
      meCalls += 1;
      await gate;
      return HttpResponse.json(profileFixture);
    }),
  );

  await act(async () => { mocks.fire?.('MFA_CHALLENGE_VERIFIED', sessionFor('u-1', 'aal2')); });
  await vi.waitFor(() => expect(meCalls).toBe(2)); // refetch is in flight
  expect(heading).toBeInTheDocument(); // same node, never unmounted (a spinner swap would detach it)
  expect(screen.getByRole('heading', { name: /^profile$/i })).toBe(heading);

  await act(async () => { release(); });
  await vi.waitFor(() => expect(meCalls).toBe(2));
  expect(heading).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: /^profile$/i })).toBe(heading);
});
