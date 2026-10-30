import { http, HttpResponse } from 'msw';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '@/test/server';
import { AuthProvider } from '@/auth/AuthProvider';
import AppRoutes from '@/AppRoutes';
import { profileFixture, pageOf } from '@/test/fixtures';

const BASE = 'http://localhost:8080';

const mocks = vi.hoisted(() => ({ session: null as unknown }));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({ data: { session: mocks.session } }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
      mfa: { listFactors: async () => ({ data: { totp: [], all: [] }, error: null }) },
    },
  },
}));

function LocationProbe() {
  const loc = useLocation();
  return <span data-testid="path">{loc.pathname}</span>;
}

function renderAt(route: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[route]}>
          <LocationProbe />
          <AppRoutes />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}

function signedIn(hasPreferences: boolean) {
  mocks.session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json({ ...profileFixture, hasPreferences })),
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
  );
}

describe('needs-preferences onboarding', () => {
  it('sends the home page to the setup page', async () => {
    signedIn(false);
    renderAt('/');
    expect(await screen.findByRole('heading', { name: 'Your food preferences' })).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent('/preferences/setup');
  });

  it('sends a protected page to the setup page', async () => {
    signedIn(false);
    renderAt('/me');
    expect(await screen.findByRole('heading', { name: 'Your food preferences' })).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent('/preferences/setup');
  });

  it('leaves the privacy page alone', async () => {
    signedIn(false);
    renderAt('/privacy');
    expect(await screen.findByText('Sign out')).toBeInTheDocument(); // the profile has loaded
    expect(screen.getByTestId('path')).toHaveTextContent('/privacy');
  });

  it('does not redirect a user who has saved preferences', async () => {
    signedIn(true);
    renderAt('/');
    expect(await screen.findByRole('button', { name: /Jane/ })).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent(/^\/$/);
  });
});
