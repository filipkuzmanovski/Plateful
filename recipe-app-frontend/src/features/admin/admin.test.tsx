import { http, HttpResponse } from 'msw';
import { render, screen, waitFor, within } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router';
import { AuthProvider } from '@/auth/AuthProvider';
import AppRoutes from '@/AppRoutes';
import { server } from '@/test/server';
import { newTestQueryClient, renderApp } from '@/test/utils';
import { pageOf, profileFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import type { MyProfile } from '@/lib/types';

const BASE = 'http://localhost:8080';

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({
        data: { session: { access_token: 'test-token', user: { id: 'u-1', email: 'jane@example.com' } } },
      }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
    },
  },
}));

const admin: MyProfile = { ...profileFixture, isAdmin: true };

function signedInAs(profile: MyProfile) {
  server.use(http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profile)));
}

/** Serves the admin API: the header count. */
function serveAdmin(opts: { count?: number } = {}) {
  server.use(http.get(`${BASE}/api/admin/reports/count`, () => HttpResponse.json({ count: opts.count ?? 0 })));
}

test('headerLinkOnlyForAdmins', async () => {
  signedInAs(admin);
  serveAdmin({ count: 3 });
  server.use(http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))));
  renderApp('/');

  const link = await screen.findByRole('link', { name: 'Reports, 3 open' });
  expect(link).toHaveAttribute('href', '/admin/reports');
  expect(within(link).getByText('Reports')).toBeInTheDocument();
  expect(within(link).getByText('3')).toBeInTheDocument();
  expectNoEmoji();
});

test('headerLinkHasNoBadgeWithoutOpenReports', async () => {
  signedInAs(admin);
  serveAdmin({ count: 0 });
  server.use(http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))));
  renderApp('/');

  const link = await screen.findByRole('link', { name: 'Reports' });
  await waitFor(() => expect(link).toHaveTextContent(/^Reports$/));
});

test('headerLinkAbsentForNonAdmins', async () => {
  let countAsked = false;
  signedInAs(profileFixture);
  server.use(
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/admin/reports/count`, () => {
      countAsked = true;
      return HttpResponse.json({ count: 3 });
    }),
  );
  renderApp('/');

  expect(await screen.findByRole('button', { name: /jane/i })).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /reports/i })).not.toBeInTheDocument();
  expect(countAsked).toBe(false);
});

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>;
}

test.each(['/admin/review', '/admin/review/x'])('%s redirects to the reports page', async (from) => {
  signedInAs(admin);
  serveAdmin();
  render(
    <QueryClientProvider client={newTestQueryClient()}>
      <AuthProvider>
        <MemoryRouter initialEntries={[from]}>
          <AppRoutes />
          <Where />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
  await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/admin/reports'));
});
