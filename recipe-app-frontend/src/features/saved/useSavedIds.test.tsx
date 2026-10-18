import { http, HttpResponse } from 'msw';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { toast } from 'sonner';
import { server } from '@/test/server';
import { AuthProvider } from '@/auth/AuthProvider';
import { useSavedIds } from './useSavedIds';

const BASE = 'http://localhost:8080';

const mocks = vi.hoisted(() => ({ session: null as unknown }));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({ data: { session: mocks.session } }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
    },
  },
}));

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

const ME = { id: 'u-1', firstName: 'Jane', lastName: 'Doe', isAdmin: false, rejectedCount: 0 };

function Probe() {
  const { isSaved, isPending, toggle, ready } = useSavedIds();
  const loc = useLocation();
  return (
    <div>
      <span data-testid="ready">{String(ready)}</span>
      <span data-testid="saved">{String(isSaved('r-1'))}</span>
      <span data-testid="saved2">{String(isSaved('r-2'))}</span>
      <span data-testid="pending">{String(isPending('r-1'))}</span>
      <span data-testid="loc">{loc.pathname}</span>
      <span data-testid="from">{(loc.state as { from?: string } | null)?.from ?? ''}</span>
      <button onClick={() => toggle('r-1')}>toggle</button>
      <button onClick={() => { toggle('r-1'); toggle('r-1'); }}>double</button>
      <button onClick={() => { toggle('r-1'); toggle('r-2'); }}>both</button>
    </div>
  );
}

function renderProbe(route = '/recipes?x=1') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[route]}>
          <Probe />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
  return queryClient;
}

function signedIn() {
  mocks.session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  server.use(http.get(`${BASE}/api/users/me`, () => HttpResponse.json(ME)));
}

/** A tiny fake server: PUT/DELETE mutate the id list the GET returns. */
function fakeSaved(
  ids: string[],
  opts: { gate?: Promise<void>; putGate?: Promise<void>; failPut?: string[] } = {},
) {
  const state = { ids: [...ids], gets: 0, puts: 0, deletes: 0, gateNextGet: opts.gate };
  server.use(
    http.get(`${BASE}/api/users/me/saved/ids`, async () => {
      state.gets++;
      const snapshot = [...state.ids];
      const g = state.gateNextGet;
      state.gateNextGet = undefined;
      if (g) await g;
      return HttpResponse.json(snapshot);
    }),
    http.put(`${BASE}/api/users/me/saved/:id`, async ({ params }) => {
      state.puts++;
      if (opts.putGate) await opts.putGate;
      const id = params.id as string;
      if (opts.failPut?.includes(id)) {
        return HttpResponse.json({ status: 500, detail: 'boom' }, { status: 500 });
      }
      if (!state.ids.includes(id)) state.ids.push(id);
      return new HttpResponse(null, { status: 204 });
    }),
    http.delete(`${BASE}/api/users/me/saved/:id`, ({ params }) => {
      state.deletes++;
      state.ids = state.ids.filter((x) => x !== params.id);
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return state;
}

beforeEach(() => {
  mocks.session = null;
  vi.mocked(toast.error).mockClear();
});

test('loadsIdsWhenSignedIn', async () => {
  signedIn();
  fakeSaved(['r-1']);
  renderProbe();
  await waitFor(() => expect(screen.getByTestId('ready')).toHaveTextContent('true'));
  expect(screen.getByTestId('saved')).toHaveTextContent('true');
});

test('toggleSavesOptimistically', async () => {
  signedIn();
  let release!: () => void;
  const putGate = new Promise<void>((r) => { release = r; });
  fakeSaved([], { putGate });
  renderProbe();
  await waitFor(() => expect(screen.getByTestId('ready')).toHaveTextContent('true'));
  await userEvent.click(screen.getByText('toggle'));
  await waitFor(() => expect(screen.getByTestId('saved')).toHaveTextContent('true')); // PUT still held
  expect(screen.getByTestId('pending')).toHaveTextContent('true');
  release();
  await waitFor(() => expect(screen.getByTestId('pending')).toHaveTextContent('false'));
  expect(screen.getByTestId('saved')).toHaveTextContent('true');
});

test('rollsBackAndToastsOnError', async () => {
  signedIn();
  fakeSaved([], { failPut: ['r-1'] });
  renderProbe();
  await waitFor(() => expect(screen.getByTestId('ready')).toHaveTextContent('true'));
  await userEvent.click(screen.getByText('toggle'));
  await waitFor(() =>
    expect(toast.error).toHaveBeenCalledWith("Couldn't update saved recipes. Please try again."),
  );
  await waitFor(() => expect(screen.getByTestId('saved')).toHaveTextContent('false'));
  expect(screen.getByTestId('pending')).toHaveTextContent('false');
});

test('signedOutTogglesToLogin', async () => {
  const state = fakeSaved([]);
  renderProbe('/recipes?x=1');
  await waitFor(() => expect(screen.getByTestId('ready')).toHaveTextContent('false'));
  await userEvent.click(screen.getByText('toggle'));
  expect(screen.getByTestId('loc')).toHaveTextContent('/login');
  expect(screen.getByTestId('from')).toHaveTextContent('/recipes?x=1');
  expect(state.puts + state.deletes).toBe(0);
});

test('ignoresSecondToggleWhilePending', async () => {
  signedIn();
  const state = fakeSaved([], { putGate: new Promise((r) => setTimeout(r, 50)) });
  renderProbe();
  await waitFor(() => expect(screen.getByTestId('ready')).toHaveTextContent('true'));
  await userEvent.click(screen.getByText('double'));
  await waitFor(() => expect(screen.getByTestId('pending')).toHaveTextContent('false'));
  expect(state.puts).toBe(1);
  expect(state.deletes).toBe(0);
  expect(screen.getByTestId('saved')).toHaveTextContent('true');
});

test('refetchDuringToggleDoesNotRevert', async () => {
  signedIn();
  let releaseGet!: () => void;
  let releasePut!: () => void;
  const state = fakeSaved([], { putGate: new Promise<void>((r) => { releasePut = r; }) });
  const qc = renderProbe();
  await waitFor(() => expect(screen.getByTestId('ready')).toHaveTextContent('true'));

  // A refetch starts and holds its (old, empty) response.
  state.gateNextGet = new Promise<void>((r) => { releaseGet = r; });
  void qc.refetchQueries({ queryKey: ['savedIds', 'u-1'] });
  await waitFor(() => expect(state.gets).toBe(2));

  await userEvent.click(screen.getByText('toggle'));
  await waitFor(() => expect(screen.getByTestId('saved')).toHaveTextContent('true'));

  releaseGet(); // the stale GET lands while the PUT is still in flight
  await new Promise((r) => setTimeout(r, 100));
  expect(screen.getByTestId('saved')).toHaveTextContent('true');

  releasePut();
  await waitFor(() => expect(screen.getByTestId('pending')).toHaveTextContent('false'));
  await waitFor(() => expect(state.gets).toBe(3)); // re-synced after settle
  expect(screen.getByTestId('saved')).toHaveTextContent('true');
});

test('toggleIgnoredBeforeIdsLoad', async () => {
  signedIn();
  let releaseGet!: () => void;
  const state = fakeSaved(['r-1'], { gate: new Promise<void>((r) => { releaseGet = r; }) });
  renderProbe();
  await waitFor(() => expect(state.gets).toBe(1));
  await userEvent.click(screen.getByText('toggle'));
  expect(state.puts + state.deletes).toBe(0);
  expect(screen.getByTestId('pending')).toHaveTextContent('false');
  releaseGet();
  await waitFor(() => expect(screen.getByTestId('saved')).toHaveTextContent('true'));
  expect(state.puts + state.deletes).toBe(0);
});

test('twoRecipesOneFails', async () => {
  signedIn();
  const state = fakeSaved([], { failPut: ['r-2'] });
  renderProbe();
  await waitFor(() => expect(screen.getByTestId('ready')).toHaveTextContent('true'));
  await userEvent.click(screen.getByText('both'));
  await waitFor(() => expect(toast.error).toHaveBeenCalledTimes(1));
  await waitFor(() => expect(screen.getByTestId('saved2')).toHaveTextContent('false'));
  await waitFor(() => expect(screen.getByTestId('pending')).toHaveTextContent('false'));
  expect(screen.getByTestId('saved')).toHaveTextContent('true');
  expect(state.puts).toBe(2);
});
