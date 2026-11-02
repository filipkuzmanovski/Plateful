import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { pageOf, profileFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import type { MyProfile } from '@/lib/types';

const BASE = 'http://localhost:8080';

const mocks = vi.hoisted(() => ({ signedIn: true }));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({
        data: {
          session: mocks.signedIn
            ? { access_token: 'test-token', user: { id: 'u-1', email: 'jane@example.com' } }
            : null,
        },
      }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
    },
  },
}));

function serve(profile: MyProfile, pending = 3) {
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profile)),
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/admin/recipes/pending/count`, () => HttpResponse.json({ count: pending })),
  );
}

beforeEach(() => {
  mocks.signedIn = true;
});

test('adminUserMenuHasPhoneLinksProfileAndSignOut', async () => {
  serve({ ...profileFixture, isAdmin: true }, 3);
  renderApp('/');

  await userEvent.click(await screen.findByRole('button', { name: /jane/i }));
  const menu = screen.getByTestId('user-menu');
  expect(await within(menu).findByRole('link', { name: 'Review queue, 3 pending' })).toHaveAttribute('href', '/admin/review');
  expect(within(menu).getByRole('link', { name: 'New recipe' })).toHaveAttribute('href', '/recipes/new');
  expect(within(menu).getByRole('link', { name: 'My profile' })).toHaveAttribute('href', '/me');
  expect(within(menu).getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  expectNoEmoji();
});

test('nonAdminUserMenuHasNoReviewQueue', async () => {
  serve(profileFixture);
  renderApp('/');

  await userEvent.click(await screen.findByRole('button', { name: /jane/i }));
  const menu = screen.getByTestId('user-menu');
  expect(within(menu).getByRole('link', { name: 'New recipe' })).toBeInTheDocument();
  expect(within(menu).getByRole('link', { name: 'My profile' })).toBeInTheDocument();
  expect(within(menu).queryByRole('link', { name: /review queue/i })).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /review queue/i })).not.toBeInTheDocument();
});

test('signedOutHeaderHasThemeButtonAndSignIn', async () => {
  mocks.signedIn = false;
  server.use(http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))));
  renderApp('/');

  expect(await screen.findByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login');
  expect(screen.getByRole('button', { name: 'Theme: Match my device' })).toBeInTheDocument();
  expect(within(screen.getByRole('banner')).getByRole('link', { name: 'Browse' })).toBeInTheDocument();
  expectNoEmoji();
});

test('phoneHiddenHeaderLinksNeverCarryABareDisplayClass', async () => {
  serve({ ...profileFixture, isAdmin: true }, 3);
  renderApp('/');

  const header = within(screen.getByRole('banner'));
  const wideOnly = [
    await header.findByRole('link', { name: 'Review queue, 3 pending' }),
    header.getByRole('link', { name: 'New recipe' }),
    header.getByRole('link', { name: 'Browse' }),
  ];
  for (const link of wideOnly) {
    const tokens = link.className.split(/\s+/);
    expect(tokens).toContain('hidden');
    expect(tokens).toContain('sm:inline-flex');
    // A bare display class ties with `hidden` and wins by CSS order, so the link would show on phones.
    for (const display of ['inline-flex', 'flex', 'block', 'inline-block', 'inline', 'grid']) {
      expect(tokens).not.toContain(display);
    }
  }
  // Cook stays on phones.
  expect(header.getByRole('link', { name: 'Cook' }).className.split(/\s+/)).not.toContain('hidden');
});

test('userMenuItemsOnlyForPhonesDoNotCarryHiddenOrInlineDisplayConflicts', async () => {
  serve({ ...profileFixture, isAdmin: true }, 3);
  renderApp('/');

  await userEvent.click(await screen.findByRole('button', { name: /jane/i }));
  const menu = within(screen.getByTestId('user-menu'));
  for (const name of ['New recipe', 'Review queue, 3 pending']) {
    const tokens = (await menu.findByRole('link', { name })).className.split(/\s+/);
    expect(tokens).toContain('sm:hidden');
    expect(tokens).not.toContain('hidden');
  }
});
