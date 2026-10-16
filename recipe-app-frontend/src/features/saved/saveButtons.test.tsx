import { http, HttpResponse } from 'msw';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '@/test/server';
import { AuthProvider } from '@/auth/AuthProvider';
import AppRoutes from '@/AppRoutes';
import { recipeFixture, profileFixture, pageOf } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';

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

function LocationProbe() {
  const loc = useLocation();
  return <span data-testid="loc">{loc.pathname}</span>;
}

function renderAt(route: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 30_000 }, mutations: { retry: false } },
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

function signedIn() {
  mocks.session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  server.use(http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)));
}

/** A tiny fake server for the saved endpoints; records the calls made. */
function fakeSaved(ids: string[] = [], opts: { putGate?: Promise<void> } = {}) {
  const state = { ids: [...ids], puts: [] as string[], deletes: [] as string[] };
  server.use(
    http.get(`${BASE}/api/users/me/saved/ids`, () => HttpResponse.json([...state.ids])),
    http.put(`${BASE}/api/users/me/saved/:id`, async ({ params }) => {
      const id = params.id as string;
      state.puts.push(id);
      if (opts.putGate) await opts.putGate;
      if (!state.ids.includes(id)) state.ids.push(id);
      return new HttpResponse(null, { status: 204 });
    }),
    http.delete(`${BASE}/api/users/me/saved/:id`, ({ params }) => {
      const id = params.id as string;
      state.deletes.push(id);
      state.ids = state.ids.filter((x) => x !== id);
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return state;
}

function stubBrowse() {
  server.use(
    http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json([])),
    http.get(`${BASE}/api/recipes/seas`, () => HttpResponse.json([])),
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([recipeFixture()]))),
  );
}

function stubDetail() {
  server.use(
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(recipeFixture())),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
  );
}

const TITLE = 'Bruschetta Pork & Pasta';

beforeEach(() => {
  mocks.session = null;
});

describe('card bookmark', () => {
  test('cardBookmarkSavesWithoutNavigating', async () => {
    signedIn();
    const state = fakeSaved();
    stubBrowse();
    renderAt('/');

    const button = await screen.findByRole('button', { name: `Save ${TITLE}` });
    await waitFor(() => expect(button).toBeEnabled());
    expect(button).toHaveAttribute('aria-pressed', 'false');

    await userEvent.click(button);
    await waitFor(() => expect(state.puts).toEqual(['r-1']));
    const saved = await screen.findByRole('button', { name: `Remove ${TITLE} from saved` });
    expect(saved).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('loc')).toHaveTextContent(/^\/$/);
    expectNoEmoji();
  });

  test.each([
    ['Enter', '{Enter}'],
    ['Space', ' '],
  ])('cardBookmarkWorksWithKeyboard %s', async (_name, key) => {
    signedIn();
    const state = fakeSaved();
    stubBrowse();
    renderAt('/');

    const button = await screen.findByRole('button', { name: `Save ${TITLE}` });
    await waitFor(() => expect(button).toBeEnabled());
    button.focus();
    await userEvent.keyboard(key);

    await waitFor(() => expect(state.puts).toEqual(['r-1']));
    expect(await screen.findByRole('button', { name: `Remove ${TITLE} from saved` })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByTestId('loc')).toHaveTextContent(/^\/$/);
  });

  test('cardShowsSavedStateFromServer', async () => {
    signedIn();
    fakeSaved(['r-1']);
    stubBrowse();
    renderAt('/');
    expect(await screen.findByRole('button', { name: `Remove ${TITLE} from saved` })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  test('bookmarkNotInsideLink', async () => {
    signedIn();
    fakeSaved();
    stubBrowse();
    renderAt('/');
    const button = await screen.findByRole('button', { name: `Save ${TITLE}` });
    expect(button.closest('a')).toBeNull();
    // The card link is still there, as a sibling.
    expect(screen.getByRole('link', { name: new RegExp(TITLE) })).toBeInTheDocument();
  });

  test('bookmarkDisabledUntilSavedIdsLoad', async () => {
    signedIn();
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    const state = fakeSaved();
    server.use(
      http.get(`${BASE}/api/users/me/saved/ids`, async () => {
        await gate;
        return HttpResponse.json([...state.ids]);
      }),
    );
    stubBrowse();
    renderAt('/');
    const button = await screen.findByRole('button', { name: `Save ${TITLE}` });
    expect(button).toBeDisabled();
    release();
    await waitFor(() => expect(button).toBeEnabled());
  });

  test('bookmarkKeepsFocusWhileSaving', async () => {
    signedIn();
    let release!: () => void;
    const state = fakeSaved([], { putGate: new Promise<void>((r) => { release = r; }) });
    stubBrowse();
    renderAt('/');

    const button = await screen.findByRole('button', { name: `Save ${TITLE}` });
    await waitFor(() => expect(button).toBeEnabled());
    await userEvent.click(button);
    await waitFor(() => expect(state.puts).toEqual(['r-1'])); // the PUT is held open

    // Not `disabled` (that would drop focus to the body); announced as unavailable instead.
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).not.toBeDisabled();
    expect(document.activeElement).toBe(button);

    await userEvent.click(button);
    expect(state.puts).toEqual(['r-1']);
    expect(state.deletes).toEqual([]);

    release();
    await waitFor(() => expect(button).not.toHaveAttribute('aria-disabled'));
    expect(document.activeElement).toBe(button);
    expect(button).toHaveAttribute('aria-pressed', 'true');
  });

  test('signedOutBookmarkGoesToLogin', async () => {
    const state = fakeSaved();
    stubBrowse();
    renderAt('/');
    const button = await screen.findByRole('button', { name: `Save ${TITLE}` });
    await waitFor(() => expect(button).toBeEnabled()); // the startup auth check settles first
    await userEvent.click(button);
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByTestId('loc')).toHaveTextContent('/login');
    expect(state.puts).toEqual([]);
  });
});

describe('recipe page save button', () => {
  test('detailSaveToggles', async () => {
    signedIn();
    const state = fakeSaved();
    stubDetail();
    renderAt('/recipes/r-1');

    const save = await screen.findByRole('button', { name: 'Save' });
    await waitFor(() => expect(save).toBeEnabled());
    expect(save).toHaveAttribute('aria-pressed', 'false');

    await userEvent.click(save);
    await waitFor(() => expect(state.puts).toEqual(['r-1']));
    const saved = await screen.findByRole('button', { name: 'Saved' });
    expect(saved).toHaveAttribute('aria-pressed', 'true');
    await waitFor(() => expect(saved).toBeEnabled());

    await userEvent.click(saved);
    await waitFor(() => expect(state.deletes).toEqual(['r-1']));
    expect(await screen.findByRole('button', { name: 'Save' })).toHaveAttribute('aria-pressed', 'false');
    expectNoEmoji();
  });

  test('detailSaveKeepsFocusWhileSaving', async () => {
    signedIn();
    let release!: () => void;
    const state = fakeSaved([], { putGate: new Promise<void>((r) => { release = r; }) });
    stubDetail();
    renderAt('/recipes/r-1');

    const save = await screen.findByRole('button', { name: 'Save' });
    await waitFor(() => expect(save).toBeEnabled());
    await userEvent.click(save);
    await waitFor(() => expect(state.puts).toEqual(['r-1']));

    const pending = screen.getByRole('button', { name: 'Saved' });
    expect(pending).toHaveAttribute('aria-disabled', 'true');
    expect(pending).not.toBeDisabled();
    expect(document.activeElement).toBe(pending);

    await userEvent.click(pending);
    expect(state.puts).toEqual(['r-1']);
    expect(state.deletes).toEqual([]);

    release();
    await waitFor(() => expect(pending).not.toHaveAttribute('aria-disabled'));
    expect(document.activeElement).toBe(pending);
  });

  test('detailSignedOutSaveGoesToLogin', async () => {
    const state = fakeSaved();
    stubDetail();
    renderAt('/recipes/r-1');
    const save = await screen.findByRole('button', { name: 'Save' });
    await waitFor(() => expect(save).toBeEnabled());
    await userEvent.click(save);
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(state.puts).toEqual([]);
  });
});
