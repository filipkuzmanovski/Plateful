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

const ME = { id: 'u-1', firstName: 'Jane', lastName: 'Doe' };

function Probe() {
  const { isSaved, isPending, toggle, ready } = useSavedIds();
  const loc = useLocation();
  return (
    <div>
      <span data-testid="ready">{String(ready)}</span>
      <span data-testid="saved">{String(isSaved('r-1'))}</span>
      <span data-testid="pending">{String(isPending('r-1'))}</span>
      <span data-testid="loc">{loc.pathname}</span>
      <span data-testid="from">{(loc.state as { from?: string } | null)?.from ?? ''}</span>
      <button onClick={() => toggle('r-1')}>toggle</button>
      <button onClick={() => { toggle('r-1'); toggle('r-1'); }}>double</button>
    </div>
  );
}

function renderProbe(route = '/recipes?x=1') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[route]}>
          <Probe />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}

function signedIn(ids: string[] = []) {
  mocks.session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(ME)),
    http.get(`${BASE}/api/users/me/saved/ids`, () => HttpResponse.json(ids)),
  );
}

beforeEach(() => {
  mocks.session = null;
  vi.mocked(toast.error).mockClear();
});

test('loadsIdsWhenSignedIn', async () => {
  signedIn(['r-1']);
  renderProbe();
  await waitFor(() => expect(screen.getByTestId('ready')).toHaveTextContent('true'));
  expect(screen.getByTestId('saved')).toHaveTextContent('true');
});

test('toggleSavesOptimistically', async () => {
  signedIn([]);
  let release!: () => void;
  const gate = new Promise<void>((r) => { release = r; });
  server.use(
    http.put(`${BASE}/api/users/me/saved/r-1`, async () => {
      await gate;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderProbe();
  await waitFor(() => expect(screen.getByTestId('ready')).toHaveTextContent('true'));
  await userEvent.click(screen.getByText('toggle'));
  expect(screen.getByTestId('saved')).toHaveTextContent('true'); // before the PUT resolves
  expect(screen.getByTestId('pending')).toHaveTextContent('true');
  release();
  await waitFor(() => expect(screen.getByTestId('pending')).toHaveTextContent('false'));
  expect(screen.getByTestId('saved')).toHaveTextContent('true');
});

test('rollsBackAndToastsOnError', async () => {
  signedIn([]);
  server.use(
    http.put(`${BASE}/api/users/me/saved/r-1`, () =>
      HttpResponse.json({ status: 500, detail: 'boom' }, { status: 500 }),
    ),
  );
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
  let calls = 0;
  server.use(
    http.put(`${BASE}/api/users/me/saved/r-1`, () => { calls++; return new HttpResponse(null, { status: 204 }); }),
    http.delete(`${BASE}/api/users/me/saved/r-1`, () => { calls++; return new HttpResponse(null, { status: 204 }); }),
  );
  renderProbe('/recipes?x=1');
  await waitFor(() => expect(screen.getByTestId('ready')).toHaveTextContent('false'));
  await userEvent.click(screen.getByText('toggle'));
  expect(screen.getByTestId('loc')).toHaveTextContent('/login');
  expect(screen.getByTestId('from')).toHaveTextContent('/recipes?x=1');
  expect(calls).toBe(0);
});

test('ignoresSecondToggleWhilePending', async () => {
  signedIn([]);
  let puts = 0;
  let deletes = 0;
  server.use(
    http.put(`${BASE}/api/users/me/saved/r-1`, async () => {
      puts++;
      await new Promise((r) => setTimeout(r, 50));
      return new HttpResponse(null, { status: 204 });
    }),
    http.delete(`${BASE}/api/users/me/saved/r-1`, () => { deletes++; return new HttpResponse(null, { status: 204 }); }),
  );
  renderProbe();
  await waitFor(() => expect(screen.getByTestId('ready')).toHaveTextContent('true'));
  await userEvent.click(screen.getByText('double'));
  await waitFor(() => expect(screen.getByTestId('pending')).toHaveTextContent('false'));
  expect(puts).toBe(1);
  expect(deletes).toBe(0);
  expect(screen.getByTestId('saved')).toHaveTextContent('true');
});
