import { http, HttpResponse } from 'msw';
import { act, cleanup, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { expectNoEmoji } from '@/test/noEmoji';
import { profileFixture, pageOf } from '@/test/fixtures';
import {
  IDLE_TIMEOUT_MS,
  LAST_ACTIVITY_KEY,
  SIGNED_OUT_IDLE_KEY,
} from './idleTimeout';

const BASE = 'http://localhost:8080';
const MIN = 60 * 1000;

const mocks = vi.hoisted(() => ({
  session: null as unknown,
  fire: undefined as undefined | ((event: string, session: unknown) => void),
  signOut: undefined as undefined | (() => Promise<unknown>),
}));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({ data: { session: mocks.session } }),
      onAuthStateChange: (cb: (event: string, session: unknown) => void) => {
        mocks.fire = cb;
        return { data: { subscription: { unsubscribe() {} } } };
      },
      signOut: () => mocks.signOut!(),
    },
  },
}));

const signedIn = { access_token: 'test-token', user: { id: 'u-1', email: 'jane@example.com' } };

async function advance(ms: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms); });
}

async function boot(route = '/') {
  renderApp(route);
  // Let the mocked session and the /me request settle on fake timers.
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
  await vi.waitFor(() => expect(screen.queryByText(/loading/i)).not.toBeInTheDocument());
}

const warning = () => screen.queryByRole('dialog', { name: /still there/i });

// Testing Library only drains its own timers under fake timers when it sees a `jest` global.
beforeAll(() => { (globalThis as { jest?: unknown }).jest = { advanceTimersByTime: vi.advanceTimersByTime }; });
afterAll(() => { delete (globalThis as { jest?: unknown }).jest; });

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: false, toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
  localStorage.clear();
  sessionStorage.clear();
  mocks.session = signedIn;
  mocks.fire = undefined;
  mocks.signOut = vi.fn(async () => {
    mocks.session = null;
    mocks.fire?.('SIGNED_OUT', null);
    return {};
  });
  server.use(
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
  );
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

test('noSignOutWhileActive', async () => {
  await boot();
  await advance(50 * MIN);
  act(() => { window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a' })); });
  await advance(50 * MIN);
  expect(mocks.signOut).not.toHaveBeenCalled();
  expect(warning()).not.toBeInTheDocument();
});

test('warningAt59Minutes', async () => {
  await boot();
  await advance(59 * MIN);
  expect(warning()).toBeInTheDocument();
  expect(screen.getByText("You'll be signed out in 60 seconds due to inactivity.")).toBeInTheDocument();
  await advance(10 * 1000);
  expect(screen.getByText("You'll be signed out in 50 seconds due to inactivity.")).toBeInTheDocument();
  expectNoEmoji();
});

test('staySignedInResets', async () => {
  await boot();
  await advance(59 * MIN);
  const stay = screen.getByRole('button', { name: /stay signed in/i });
  await act(async () => { stay.click(); });
  expect(warning()).not.toBeInTheDocument();
  await advance(58 * MIN);
  expect(warning()).not.toBeInTheDocument();
  expect(mocks.signOut).not.toHaveBeenCalled();
});

test('activityWhileWarningClosesIt', async () => {
  await boot();
  await advance(59 * MIN + 5000);
  expect(warning()).toBeInTheDocument();
  act(() => { window.dispatchEvent(new Event('pointerdown')); });
  expect(warning()).not.toBeInTheDocument();
  await advance(58 * MIN);
  expect(mocks.signOut).not.toHaveBeenCalled();
});

test('signsOutAt60AndLoginShowsMessage', async () => {
  await boot();
  await advance(IDLE_TIMEOUT_MS);
  expect(mocks.signOut).toHaveBeenCalledTimes(1);
  await advance(0);
  expect(screen.getByRole('heading', { name: /^sign in$/i })).toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('You were signed out after 60 minutes of inactivity.');
  expect(sessionStorage.getItem(SIGNED_OUT_IDLE_KEY)).toBeNull();

  // Shown once: a fresh visit to the page has no message.
  cleanup();
  mocks.session = null;
  await boot('/login');
  expect(screen.getByRole('heading', { name: /^sign in$/i })).toBeInTheDocument();
  expect(screen.queryByText(/signed out after 60 minutes/i)).not.toBeInTheDocument();
  await advance(IDLE_TIMEOUT_MS);
  expect(mocks.signOut).toHaveBeenCalledTimes(1);
});

test('otherTabActivityResets', async () => {
  await boot();
  await advance(55 * MIN);
  const now = Date.now();
  localStorage.setItem(LAST_ACTIVITY_KEY, String(now));
  act(() => {
    window.dispatchEvent(new StorageEvent('storage', { key: LAST_ACTIVITY_KEY, newValue: String(now) }));
  });
  await advance(10 * MIN);
  expect(warning()).not.toBeInTheDocument();
  expect(mocks.signOut).not.toHaveBeenCalled();
});

test('returnAfterSleepSignsOutImmediately', async () => {
  await boot();
  vi.setSystemTime(Date.now() + 61 * MIN); // the computer slept: no timers ran
  await act(async () => {
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.advanceTimersByTimeAsync(0);
  });
  expect(mocks.signOut).toHaveBeenCalledTimes(1);
  expect(warning()).not.toBeInTheDocument();
});

test('returnAfterSleepOnWindowFocusSignsOut', async () => {
  await boot();
  vi.setSystemTime(Date.now() + 61 * MIN);
  await act(async () => {
    window.dispatchEvent(new Event('focus'));
    await vi.advanceTimersByTimeAsync(0);
  });
  expect(mocks.signOut).toHaveBeenCalledTimes(1);
  expect(warning()).not.toBeInTheDocument();
});

const realUser = () => userEvent.setup({ advanceTimers: vi.advanceTimersByTime });

test('signOutNowButton', async () => {
  await boot();
  await advance(59 * MIN);
  await act(async () => { screen.getByRole('button', { name: /sign out now/i }).click(); });
  await advance(0);
  expect(mocks.signOut).toHaveBeenCalledTimes(1);
  expect(screen.getByRole('heading', { name: /^sign in$/i })).toBeInTheDocument();
  expect(screen.queryByRole('status')).not.toBeInTheDocument(); // a chosen sign-out is not an inactivity message
  expect(sessionStorage.getItem(SIGNED_OUT_IDLE_KEY)).toBeNull();
  await advance(IDLE_TIMEOUT_MS);
  expect(mocks.signOut).toHaveBeenCalledTimes(1);
});

test('signOutNowWorksWithARealPointerSequence', async () => {
  await boot();
  await advance(59 * MIN);
  // pointerdown, mousedown, pointerup, mouseup, click: the dialog must survive until the click.
  await realUser().click(screen.getByRole('button', { name: /sign out now/i }));
  await advance(0);
  expect(mocks.signOut).toHaveBeenCalledTimes(1);
  expect(screen.getByRole('heading', { name: /^sign in$/i })).toBeInTheDocument();
  expect(screen.queryByRole('status')).not.toBeInTheDocument();
});

test('signOutNowWorksFromTheKeyboard', async () => {
  await boot();
  await advance(59 * MIN);
  const user = realUser();
  await user.tab(); // Tab inside the dialog is not activity
  const signOutNow = screen.getByRole('button', { name: /sign out now/i });
  expect(warning()).toBeInTheDocument();
  expect(signOutNow).toHaveFocus();
  await user.keyboard('{Enter}');
  await advance(0);
  expect(mocks.signOut).toHaveBeenCalledTimes(1);
});

test('staySignedInWorksWithARealPointerSequence', async () => {
  await boot();
  await advance(59 * MIN);
  await realUser().click(screen.getByRole('button', { name: /stay signed in/i }));
  expect(warning()).not.toBeInTheDocument();
  await advance(58 * MIN);
  expect(warning()).not.toBeInTheDocument();
  expect(mocks.signOut).not.toHaveBeenCalled();
});

test('staySignedInHasFocusAndEnterKeepsTheUserSignedIn', async () => {
  await boot();
  await advance(59 * MIN);
  const stay = screen.getByRole('button', { name: /stay signed in/i });
  expect(stay).toHaveFocus();
  await realUser().keyboard('{Enter}');
  expect(warning()).not.toBeInTheDocument();
  expect(mocks.signOut).not.toHaveBeenCalled();
  await advance(58 * MIN);
  expect(warning()).not.toBeInTheDocument();
  expect(mocks.signOut).not.toHaveBeenCalled();
});

test('tabAndPointerInsideTheDialogDoNotDismissIt', async () => {
  await boot();
  await advance(59 * MIN);
  const user = realUser();
  await user.tab(); await user.tab();
  await user.pointer({ keys: '[MouseLeft>]', target: screen.getByText(/signed out in/i) });
  expect(warning()).toBeInTheDocument();
});

test('notActiveWhenAnonymous', async () => {
  mocks.session = null;
  await boot();
  await advance(2 * 60 * MIN);
  expect(warning()).not.toBeInTheDocument();
  expect(mocks.signOut).not.toHaveBeenCalled();
});

test('staleTimestampFromOldSessionIgnored', async () => {
  localStorage.setItem(LAST_ACTIVITY_KEY, String(Date.now() - 3 * 60 * MIN));
  await boot();
  expect(mocks.signOut).not.toHaveBeenCalled();
  expect(warning()).not.toBeInTheDocument();
  await advance(30 * MIN);
  expect(mocks.signOut).not.toHaveBeenCalled();
});

test('activityIsThrottledButStillWritten', async () => {
  await boot();
  await advance(10 * 1000);
  act(() => { window.dispatchEvent(new Event('wheel')); });
  expect(Number(localStorage.getItem(LAST_ACTIVITY_KEY))).toBe(Date.now());
});
