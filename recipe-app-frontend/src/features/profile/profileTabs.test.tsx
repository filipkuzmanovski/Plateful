import { http, HttpResponse } from 'msw';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation, useNavigate } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '@/test/server';
import { AuthProvider } from '@/auth/AuthProvider';
import AppRoutes from '@/AppRoutes';
import { recipeFixture, profileFixture, pageOf } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import type { RecipeDisplay, SavedItem } from '@/lib/types';

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
  const navigate = useNavigate();
  return (
    <>
      <span data-testid="path">{loc.pathname}</span>
      <span data-testid="search">{loc.search}</span>
      <button onClick={() => navigate(-1)}>history back</button>
    </>
  );
}

function newQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 30_000 }, mutations: { retry: false } },
  });
}

function renderAt(route: string | string[], index?: number, queryClient = newQueryClient()) {
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={Array.isArray(route) ? route : [route]} initialIndex={index}>
          <LocationProbe />
          <AppRoutes />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}

const mine = [recipeFixture({ id: 'm-1', title: 'My Own Pie' })];

function signedIn() {
  mocks.session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/users/u-1/recipes`, () => HttpResponse.json(pageOf(mine))),
  );
}

/** A tiny fake saved-recipes server: the list, the ids and unsave all share one state. */
function fakeSavedServer(recipes: RecipeDisplay[]) {
  const state = { ids: recipes.map((r) => r.id), listCalls: 0, deletes: [] as string[] };
  server.use(
    http.get(`${BASE}/api/users/me/saved/ids`, () => HttpResponse.json([...state.ids])),
    http.get(`${BASE}/api/users/me/saved`, ({ request }) => {
      state.listCalls += 1;
      const url = new URL(request.url);
      const page = Number(url.searchParams.get('page') ?? '0');
      const size = Number(url.searchParams.get('size') ?? '12');
      const all = recipes.filter((r) => state.ids.includes(r.id));
      const content: SavedItem[] = all
        .slice(page * size, (page + 1) * size)
        .map((recipe) => ({ kind: 'recipe', recipe, stub: null }));
      return HttpResponse.json(
        pageOf(content, { number: page, size, totalElements: all.length, totalPages: Math.ceil(all.length / size) }),
      );
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

/** The Preferences tab's endpoints, with nothing saved yet (GET answers 404). */
function stubPreferencesApi() {
  server.use(
    http.get(`${BASE}/api/food/allergens`, () => HttpResponse.json([{ key: 'milk', label: 'Milk' }])),
    http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json([])),
    http.get(`${BASE}/api/users/me/preferences`, () => HttpResponse.json({ detail: 'none' }, { status: 404 })),
  );
}

const soup = recipeFixture({ id: 's-1', title: 'Saved Soup' });

beforeEach(() => {
  mocks.session = null;
});

describe('profile tabs', () => {
  test('tabFollowsUrl', async () => {
    signedIn();
    fakeSavedServer([soup]);
    renderAt('/me?tab=saved');

    const savedTab = await screen.findByRole('tab', { name: 'Saved' });
    expect(savedTab).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'My recipes' })).toHaveAttribute('aria-selected', 'false');
    expect(await screen.findByText('Saved Soup')).toBeInTheDocument();
    expect(screen.queryByText('My Own Pie')).not.toBeInTheDocument();

    const panel = screen.getByRole('tabpanel');
    expect(panel).toHaveAccessibleName('Saved');
    expect(panel).toContainElement(screen.getByText('Saved Soup'));
    expect(screen.getByRole('tablist')).toBeInTheDocument();
    expectNoEmoji();
  });

  test('defaultsToMyRecipesAndIgnoresUnknownTab', async () => {
    signedIn();
    const state = fakeSavedServer([soup]);
    renderAt('/me?tab=bogus');

    expect(await screen.findByRole('tab', { name: 'My recipes' })).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByText('My Own Pie')).toBeInTheDocument();
    expect(screen.getByRole('tabpanel')).toHaveAccessibleName('My recipes');
    // The profile form and two-factor settings stay above the tabs.
    expect(screen.getByLabelText(/first name/i)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Two-factor authentication' })).toBeInTheDocument();
    // Saved recipes are only fetched once the Saved tab is opened.
    expect(state.listCalls).toBe(0);
  });

  test('switchingTabUpdatesUrl', async () => {
    signedIn();
    fakeSavedServer([soup]);
    renderAt(['/', '/me?foo=1'], 1);

    const mineTab = await screen.findByRole('tab', { name: 'My recipes' });
    expect(mineTab).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('search')).toHaveTextContent('?foo=1');

    await userEvent.click(screen.getByRole('tab', { name: 'Saved' }));
    expect(await screen.findByText('Saved Soup')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Saved' })).toHaveAttribute('aria-selected', 'true');
    const search = new URLSearchParams(screen.getByTestId('search').textContent ?? '');
    expect(search.get('tab')).toBe('saved');
    expect(search.get('foo')).toBe('1'); // other params are kept

    await userEvent.click(screen.getByRole('tab', { name: 'My recipes' }));
    expect(await screen.findByText('My Own Pie')).toBeInTheDocument();
    const back = new URLSearchParams(screen.getByTestId('search').textContent ?? '');
    expect(back.get('tab')).toBeNull();
    expect(back.get('foo')).toBe('1');
  });

  test('switchingTabReplacesHistory', async () => {
    signedIn();
    fakeSavedServer([soup]);
    server.use(
      http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json([])),
      http.get(`${BASE}/api/recipes/seas`, () => HttpResponse.json([])),
      http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
    );
    renderAt(['/', '/me'], 1);

    await userEvent.click(await screen.findByRole('tab', { name: 'Saved' }));
    expect(await screen.findByText('Saved Soup')).toBeInTheDocument();
    // One history step back leaves /me entirely, so the tab switch replaced instead of pushed.
    await userEvent.click(screen.getByRole('button', { name: 'history back' }));
    await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent(/^\/$/));
  });

  test('hasThreeTabsInOrder', async () => {
    signedIn();
    fakeSavedServer([soup]);
    renderAt('/me');

    await screen.findByRole('tab', { name: 'My recipes' });
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['My recipes', 'Saved', 'Preferences']);
  });

  test('preferencesTabFollowsUrl', async () => {
    signedIn();
    fakeSavedServer([soup]);
    stubPreferencesApi();
    renderAt('/me?tab=preferences');

    const tab = await screen.findByRole('tab', { name: 'Preferences' });
    expect(tab).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'My recipes' })).toHaveAttribute('aria-selected', 'false');
    expect(await screen.findByRole('button', { name: 'Save preferences' })).toBeInTheDocument();
    expect(screen.getByRole('tabpanel')).toHaveAccessibleName('Preferences');
    expect(screen.queryByText('My Own Pie')).not.toBeInTheDocument();
    expectNoEmoji();
  });

  test('arrowKeysMoveTabs', async () => {
    signedIn();
    fakeSavedServer([soup]);
    stubPreferencesApi();
    renderAt('/me');

    const mineTab = await screen.findByRole('tab', { name: 'My recipes' });
    const savedTab = screen.getByRole('tab', { name: 'Saved' });
    const prefsTab = screen.getByRole('tab', { name: 'Preferences' });
    // Roving tabindex: only the selected tab is in the page's tab order.
    expect(mineTab).toHaveAttribute('tabindex', '0');
    expect(savedTab).toHaveAttribute('tabindex', '-1');
    expect(prefsTab).toHaveAttribute('tabindex', '-1');

    mineTab.focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(savedTab).toHaveFocus();
    expect(savedTab).toHaveAttribute('aria-selected', 'true');
    expect(savedTab).toHaveAttribute('tabindex', '0');
    expect(mineTab).toHaveAttribute('tabindex', '-1');
    expect(await screen.findByText('Saved Soup')).toBeInTheDocument();
    expect(new URLSearchParams(screen.getByTestId('search').textContent ?? '').get('tab')).toBe('saved');

    await userEvent.keyboard('{ArrowRight}');
    expect(prefsTab).toHaveFocus();
    expect(prefsTab).toHaveAttribute('aria-selected', 'true');
    expect(prefsTab).toHaveAttribute('tabindex', '0');
    expect(savedTab).toHaveAttribute('tabindex', '-1');
    expect(new URLSearchParams(screen.getByTestId('search').textContent ?? '').get('tab')).toBe('preferences');

    await userEvent.keyboard('{ArrowRight}'); // wraps to the first tab
    expect(mineTab).toHaveFocus();
    expect(mineTab).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByText('My Own Pie')).toBeInTheDocument();

    await userEvent.keyboard('{ArrowLeft}'); // wraps to the last tab
    expect(prefsTab).toHaveFocus();
    expect(prefsTab).toHaveAttribute('aria-selected', 'true');

    await userEvent.keyboard('{ArrowLeft}');
    expect(savedTab).toHaveFocus();
    expect(savedTab).toHaveAttribute('aria-selected', 'true');

    await userEvent.keyboard('{Home}');
    expect(mineTab).toHaveFocus();
    expect(mineTab).toHaveAttribute('aria-selected', 'true');
    await userEvent.keyboard('{End}');
    expect(prefsTab).toHaveFocus();
    expect(prefsTab).toHaveAttribute('aria-selected', 'true');
  });

  test('tabsAreWiredToTheirPanel', async () => {
    signedIn();
    fakeSavedServer([soup]);
    renderAt('/me');
    const mineTab = await screen.findByRole('tab', { name: 'My recipes' });
    const panel = screen.getByRole('tabpanel');
    expect(mineTab.getAttribute('aria-controls')).toBe(panel.id);
    expect(panel.getAttribute('aria-labelledby')).toBe(mineTab.id);
  });

  test('emptyStateCopy', async () => {
    signedIn();
    fakeSavedServer([]);
    renderAt('/me?tab=saved');

    expect(
      await screen.findByText('No saved recipes yet. Tap the bookmark on any recipe to save it here.'),
    ).toBeInTheDocument();
    const browse = screen.getByRole('link', { name: 'Browse recipes' });
    expect(browse).toHaveAttribute('href', '/');
    expectNoEmoji();
  });

  test('savedErrorShowsMessage', async () => {
    signedIn();
    server.use(
      http.get(`${BASE}/api/users/me/saved/ids`, () => HttpResponse.json([])),
      http.get(`${BASE}/api/users/me/saved`, () =>
        HttpResponse.json({ status: 500, detail: 'boom' }, { status: 500 }),
      ),
    );
    renderAt('/me?tab=saved');
    expect(await screen.findByText("Couldn't load saved recipes.")).toBeInTheDocument();
  });

  test('unsaveRemovesFromSavedTab', async () => {
    signedIn();
    const state = fakeSavedServer([soup]);
    renderAt('/me?tab=saved');

    const remove = await screen.findByRole('button', { name: 'Remove Saved Soup from saved' });
    await waitFor(() => expect(remove).toBeEnabled());
    await userEvent.click(remove);

    await waitFor(() => expect(state.deletes).toEqual(['s-1']));
    await waitFor(() => expect(screen.queryByText('Saved Soup')).not.toBeInTheDocument());
    expect(
      await screen.findByText('No saved recipes yet. Tap the bookmark on any recipe to save it here.'),
    ).toBeInTheDocument();
    expectNoEmoji();
  });

  test('unsavingTheLastCardOfAPageStepsBack', async () => {
    signedIn();
    const many = Array.from({ length: 13 }, (_, i) =>
      recipeFixture({ id: `s-${i + 1}`, title: `Saved Dish ${i + 1}` }),
    );
    const state = fakeSavedServer(many);
    renderAt('/me?tab=saved');

    expect(await screen.findByText('Saved Dish 1')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Go to page 2' }));
    const remove = await screen.findByRole('button', { name: 'Remove Saved Dish 13 from saved' });
    expect(screen.queryByText('Saved Dish 1')).not.toBeInTheDocument();
    await waitFor(() => expect(remove).toBeEnabled());
    await userEvent.click(remove);

    await waitFor(() => expect(state.deletes).toEqual(['s-13']));
    // Page 2 is now empty, so the panel falls back to the last page that has recipes.
    expect(await screen.findByText('Saved Dish 1')).toBeInTheDocument();
    expect(screen.queryByText('Saved Dish 13')).not.toBeInTheDocument();
    expect(screen.queryByText(/No saved recipes yet/)).not.toBeInTheDocument();
  });

  test('savedListCacheIsScopedToTheSignedInUser', async () => {
    // Account A's saved page is still in the shared cache (same tab, no cache clear on sign-out).
    // Account B signs in: it must never see A's private list, only its own.
    const queryClient = newQueryClient();
    const aList = pageOf<SavedItem>([
      { kind: 'recipe', recipe: recipeFixture({ id: 'a-1', title: 'Account A Secret Stew' }), stub: null },
    ]);
    queryClient.setQueryData(['savedRecipes', 'u-1', 0], aList);
    queryClient.setQueryData(['savedRecipes', 0], aList); // the pre-fix, unscoped key

    mocks.session = { access_token: 't', user: { id: 'u-2', email: 'bob@example.com' } };
    server.use(
      http.get(`${BASE}/api/users/me`, () => HttpResponse.json({ id: 'u-2', firstName: 'Bob', lastName: 'Smith', isAdmin: false, rejectedCount: 0, hasPreferences: true })),
      http.get(`${BASE}/api/users/u-2/recipes`, () => HttpResponse.json(pageOf([]))),
    );
    fakeSavedServer([recipeFixture({ id: 'b-1', title: 'Account B Salad' })]);
    renderAt('/me?tab=saved', undefined, queryClient);

    expect(await screen.findByText('Account B Salad')).toBeInTheDocument();
    expect(screen.queryByText('Account A Secret Stew')).not.toBeInTheDocument();
  });

  test('publicProfileHasNoSavedTab', async () => {
    signedIn();
    fakeSavedServer([soup]);
    server.use(
      http.get(`${BASE}/api/users/u-2`, () => HttpResponse.json({ id: 'u-2', firstName: 'Bob', lastName: 'Smith' })),
      http.get(`${BASE}/api/users/u-2/recipes`, () =>
        HttpResponse.json(pageOf([recipeFixture({ id: 'b-1', title: 'Bob Burgers' })])),
      ),
    );
    renderAt('/users/u-2?tab=saved');

    expect(await screen.findByText('Bob Burgers')).toBeInTheDocument();
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
    expect(screen.queryByText('Saved Soup')).not.toBeInTheDocument();
    expect(within(document.body).queryByRole('tab', { name: 'Saved' })).toBeNull();
    expectNoEmoji();
  });
});
