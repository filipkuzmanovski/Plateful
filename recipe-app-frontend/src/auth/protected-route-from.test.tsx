import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from '@/auth/AuthProvider';
import { ProtectedRoute } from '@/auth/ProtectedRoute';

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

function Login() {
  const loc = useLocation();
  return <span data-testid="from">{(loc.state as { from?: string } | null)?.from ?? ''}</span>;
}

test('anonymousVisitorKeepsTheQueryStringAcrossSignIn', async () => {
  mocks.session = null;
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/me?tab=saved']}>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/me" element={<ProtectedRoute><span>private</span></ProtectedRoute>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
  expect(await screen.findByTestId('from')).toHaveTextContent('/me?tab=saved');
});
