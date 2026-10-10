import { http, HttpResponse } from 'msw';
import { act, screen } from '@testing-library/react';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { profileFixture, pageOf } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

const mocks = vi.hoisted(() => ({
  fire: undefined as undefined | ((event: string, session: unknown) => void),
  getSession: undefined as undefined | (() => Promise<unknown>),
}));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: () => mocks.getSession!(),
      onAuthStateChange: (cb: (event: string, session: unknown) => void) => {
        mocks.fire = cb;
        return { data: { subscription: { unsubscribe() {} } } };
      },
      signOut: async () => {},
    },
  },
}));

beforeEach(() => {
  mocks.fire = undefined;
  mocks.getSession = async () => ({ data: { session: null } });
  server.use(http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))));
});

const sessionFor = (id: string) => ({ access_token: 't', user: { id, email: `${id}@example.com` } });

test('an auth event re-resolves the profile, and duplicate events fetch it only once', async () => {
  let meCalls = 0;
  server.use(
    http.get(`${BASE}/api/users/me`, () => {
      meCalls += 1;
      return HttpResponse.json(profileFixture);
    }),
  );
  renderApp('/');
  expect(await screen.findByRole('link', { name: /sign in/i })).toBeInTheDocument();

  await act(async () => { mocks.fire?.('SIGNED_IN', sessionFor('u-1')); });
  expect(await screen.findByRole('button', { name: /jane/i })).toBeInTheDocument();

  await act(async () => { mocks.fire?.('TOKEN_REFRESHED', sessionFor('u-1')); }); // same user — must not refetch
  expect(meCalls).toBe(1);
  expect(screen.getByRole('button', { name: /jane/i })).toBeInTheDocument(); // still ready
});

test('a rejected getSession lands on anonymous instead of an endless spinner', async () => {
  mocks.getSession = () => Promise.reject(new Error('network down'));
  renderApp('/');
  expect(await screen.findByRole('link', { name: /sign in/i })).toBeInTheDocument();
});

test('sign-out then sign-in as another user re-resolves the profile', async () => {
  let meCalls = 0;
  server.use(
    http.get(`${BASE}/api/users/me`, () => {
      meCalls += 1;
      return HttpResponse.json(
        meCalls === 1 ? profileFixture : { id: 'u-2', firstName: 'Bob', lastName: 'Smith' },
      );
    }),
  );
  renderApp('/');
  await screen.findByRole('link', { name: /sign in/i });

  await act(async () => { mocks.fire?.('SIGNED_IN', sessionFor('u-1')); });
  expect(await screen.findByRole('button', { name: /jane/i })).toBeInTheDocument();

  await act(async () => { mocks.fire?.('SIGNED_OUT', null); });
  expect(await screen.findByRole('link', { name: /sign in/i })).toBeInTheDocument();

  await act(async () => { mocks.fire?.('SIGNED_IN', sessionFor('u-2')); });
  expect(await screen.findByRole('button', { name: /bob/i })).toBeInTheDocument();
  expect(meCalls).toBe(2);
});

test('a transient profile failure is retried on the next auth event', async () => {
  let meCalls = 0;
  server.use(
    http.get(`${BASE}/api/users/me`, () => {
      meCalls += 1;
      return meCalls === 1
        ? HttpResponse.json({ status: 500, detail: 'boom' }, { status: 500 })
        : HttpResponse.json(profileFixture);
    }),
  );
  renderApp('/');
  await screen.findByRole('link', { name: /sign in/i });

  await act(async () => { mocks.fire?.('SIGNED_IN', sessionFor('u-1')); });
  await vi.waitFor(() => expect(meCalls).toBe(1));
  expect(await screen.findByRole('link', { name: /sign in/i })).toBeInTheDocument(); // failed → anonymous

  await act(async () => { mocks.fire?.('TOKEN_REFRESHED', sessionFor('u-1')); }); // same user, but must retry
  expect(await screen.findByRole('button', { name: /jane/i })).toBeInTheDocument();
  expect(meCalls).toBe(2);
});
