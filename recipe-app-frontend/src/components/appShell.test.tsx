import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { pageOf, profileFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import type { MyProfile } from '@/lib/types';

const BASE = 'http://localhost:8080';

const b64url = (o: unknown) =>
  btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const aal1Token = `${b64url({ alg: 'HS256' })}.${b64url({ aal: 'aal1' })}.sig`;

const mocks = vi.hoisted(() => ({ signedIn: true, needsMfa: false }));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({
        data: {
          session: mocks.signedIn
            ? { access_token: mocks.needsMfa ? aal1Token : 'test-token', user: { id: 'u-1', email: 'jane@example.com' } }
            : null,
        },
      }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
      mfa: {
        getAuthenticatorAssuranceLevel: async () => ({
          data: { currentLevel: 'aal1', nextLevel: 'aal2' },
          error: null,
        }),
        listFactors: async () => ({ data: { all: [], totp: [{ id: 'f1', friendly_name: 'Phone' }] }, error: null }),
      },
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
  mocks.needsMfa = false;
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
    expect(tokens).toContain('md:inline-flex');
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
    expect(tokens).toContain('md:hidden');
    expect(tokens).not.toContain('hidden');
  }
});


describe('while two-factor verification is pending', () => {
  beforeEach(() => {
    mocks.needsMfa = true;
    server.use(http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))));
  });

  test('browseIsWideOnlySoTheHeaderFitsOnPhones', async () => {
    renderApp('/');

    await screen.findByRole('link', { name: 'Finish signing in' });
    const tokens = within(screen.getByRole('banner')).getByRole('link', { name: 'Browse' }).className.split(/\s+/);
    expect(tokens).toContain('hidden');
    expect(tokens).toContain('md:inline-flex');
    for (const display of ['inline-flex', 'flex', 'block', 'inline-block', 'inline', 'grid']) {
      expect(tokens).not.toContain(display);
    }
  });

  test('finishSigningInLinkShowsOutsideTheVerifyPage', async () => {
    renderApp('/');

    expect(await screen.findByRole('link', { name: 'Finish signing in' })).toHaveAttribute('href', '/verify-2fa');
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });

  test('noFinishSigningInLinkOnTheVerifyPage', async () => {
    renderApp('/verify-2fa');

    expect(await screen.findByRole('heading', { name: /two-factor verification/i })).toBeInTheDocument();
    expect(within(screen.getByRole('banner')).getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Finish signing in' })).not.toBeInTheDocument();
  });
});

