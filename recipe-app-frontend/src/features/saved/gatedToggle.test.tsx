import { http, HttpResponse } from 'msw';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '@/test/server';
import { AuthProvider, useAuth } from '@/auth/AuthProvider';
import { useSavedIds } from './useSavedIds';

const BASE = 'http://localhost:8080';

const b64url = (o: unknown) =>
  btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const aal1Token = `${b64url({ alg: 'HS256' })}.${b64url({ aal: 'aal1' })}.sig`;

const mocks = vi.hoisted(() => ({ session: null as unknown }));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({ data: { session: mocks.session } }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
      mfa: {
        // A verified factor exists but this session is only aal1: the half signed-in state.
        getAuthenticatorAssuranceLevel: async () => ({
          data: { currentLevel: 'aal1', nextLevel: 'aal2' },
          error: null,
        }),
      },
    },
  },
}));

function Probe() {
  const { status } = useAuth();
  const { toggle } = useSavedIds();
  const loc = useLocation();
  return (
    <div>
      <span data-testid="status">{status}</span>
      <span data-testid="loc">{loc.pathname}</span>
      <span data-testid="from">{(loc.state as { from?: string } | null)?.from ?? ''}</span>
      <button onClick={() => toggle('r-1')}>toggle</button>
    </div>
  );
}

function renderProbe(route: string) {
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
}

let saves = 0;

beforeEach(() => {
  saves = 0;
  mocks.session = { access_token: aal1Token, user: { id: 'u-1', email: 'jane@example.com' } };
  server.use(
    http.put(`${BASE}/api/users/me/saved/:id`, () => {
      saves++;
      return new HttpResponse(null, { status: 204 });
    }),
  );
});

test('halfSignedInToggleGoesToVerifyTwoFactor', async () => {
  renderProbe('/recipes?x=1');
  await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('needs-mfa'));
  await userEvent.click(screen.getByText('toggle'));
  expect(screen.getByTestId('loc')).toHaveTextContent('/verify-2fa');
  expect(screen.getByTestId('from')).toHaveTextContent('/recipes?x=1');
  expect(saves).toBe(0);
});

test('profilelessToggleGoesToCompleteProfile', async () => {
  // Same destination as AppShell and ProtectedRoute use for this state.
  mocks.session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  server.use(
    http.get(`${BASE}/api/users/me`, () =>
      HttpResponse.json({ status: 404, detail: 'User not found' }, { status: 404 }),
    ),
  );
  renderProbe('/recipes?x=1');
  await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('needs-profile'));
  await userEvent.click(screen.getByText('toggle'));
  expect(screen.getByTestId('loc')).toHaveTextContent('/complete-profile');
  expect(saves).toBe(0);
});
