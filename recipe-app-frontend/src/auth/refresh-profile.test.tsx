import { http, HttpResponse } from 'msw';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '@/test/server';
import { AuthProvider, useAuth } from '@/auth/AuthProvider';
import { profileFixture } from '@/test/fixtures';
import type { MyProfile } from '@/lib/types';

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

const sessionFor = (id: string) => ({ access_token: 't', user: { id, email: `${id}@example.com` } });

function Probe() {
  const { profile, status, refreshProfile } = useAuth();
  return (
    <div>
      <span data-testid="status">{status}</span>
      <span data-testid="name">{profile?.firstName ?? ''}</span>
      <span data-testid="rejected">{profile?.rejectedCount ?? ''}</span>
      <button onClick={() => void refreshProfile()}>refresh</button>
    </div>
  );
}

/** Answers /api/users/me from a queue, one reply per call; a reply may be held until released. */
function queueMe(replies: Array<MyProfile | Promise<MyProfile>>) {
  server.use(http.get(`${BASE}/api/users/me`, async () => HttpResponse.json(await replies.shift()!)));
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => { resolve = r; });
  return { promise, resolve };
}

async function renderSignedInAs(id: string) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider><Probe /></AuthProvider>
    </QueryClientProvider>,
  );
  await waitFor(() => expect(mocks.fire).toBeDefined());
  await act(async () => { mocks.fire!('SIGNED_IN', sessionFor(id)); });
  await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('ready'));
}

beforeEach(() => { mocks.fire = undefined; });

test('refreshProfile updates the profile of the signed-in user', async () => {
  queueMe([profileFixture, { ...profileFixture, rejectedCount: 3 }]);
  await renderSignedInAs('u-1');
  expect(screen.getByTestId('rejected')).toHaveTextContent('0');

  await userEvent.click(screen.getByRole('button', { name: 'refresh' }));
  await waitFor(() => expect(screen.getByTestId('rejected')).toHaveTextContent('3'));
});

test('refreshProfileIgnoredAfterSignOut', async () => {
  const held = deferred<MyProfile>();
  queueMe([profileFixture, held.promise]);
  await renderSignedInAs('u-1');

  await userEvent.click(screen.getByRole('button', { name: 'refresh' }));
  await act(async () => { mocks.fire!('SIGNED_OUT', null); });
  await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('anonymous'));

  await act(async () => { held.resolve({ ...profileFixture, firstName: 'Stale' }); });
  await new Promise((r) => setTimeout(r, 50)); // let the late response land
  expect(screen.getByTestId('status')).toHaveTextContent('anonymous');
  expect(screen.getByTestId('name')).toHaveTextContent('');
  expect(screen.queryByText('Stale')).not.toBeInTheDocument();
});

test('refreshProfileIgnoredAfterUserSwitch', async () => {
  const held = deferred<MyProfile>();
  const bob = { ...profileFixture, id: 'u-2', firstName: 'Bob' };
  queueMe([profileFixture, held.promise, bob]);
  await renderSignedInAs('u-1');

  await userEvent.click(screen.getByRole('button', { name: 'refresh' }));
  await act(async () => { mocks.fire!('SIGNED_OUT', null); });
  await act(async () => { mocks.fire!('SIGNED_IN', sessionFor('u-2')); });
  await waitFor(() => expect(screen.getByTestId('name')).toHaveTextContent('Bob'));

  await act(async () => { held.resolve({ ...profileFixture, firstName: 'Stale' }); });
  await new Promise((r) => setTimeout(r, 50));
  expect(screen.getByTestId('name')).toHaveTextContent('Bob');
});
