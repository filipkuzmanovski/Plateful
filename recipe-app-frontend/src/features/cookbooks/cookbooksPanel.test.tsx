import { http, HttpResponse } from 'msw';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '@/test/server';
import { AuthProvider } from '@/auth/AuthProvider';
import AppRoutes from '@/AppRoutes';
import { pageOf, profileFixture, recipeFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import type { CookbookSummary, SavedItem } from '@/lib/types';

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

function newQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 30_000 }, mutations: { retry: false } },
  });
}

function renderAt(route: string, queryClient = newQueryClient()) {
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[route]}>
          <AppRoutes />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}

const TAB = '/me?tab=cookbooks';

const book = (id: string, volume: number, subtitle: string | null = null): CookbookSummary => ({
  id, volume, subtitle, createdAt: '2026-11-05T10:00:00Z', recipeCount: 5,
});

const likedItem = (id: string, title: string): SavedItem => ({
  kind: 'recipe', recipe: recipeFixture({ id, title, likeCount: 2 }), stub: null,
});

function signedIn({
  likedIds = [] as string[],
  cookbooks = [] as CookbookSummary[],
  liked = [] as SavedItem[],
  userId = 'u-1',
} = {}) {
  const state = { cookbookCalls: 0, deletes: [] as string[], likedIds: [...likedIds] };
  mocks.session = { access_token: 't', user: { id: userId, email: 'jane@example.com' } };
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json({ ...profileFixture, id: userId })),
    http.get(`${BASE}/api/users/${userId}/recipes`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/users/me/saved/ids`, () => HttpResponse.json([])),
    http.get(`${BASE}/api/users/me/likes/ids`, () => HttpResponse.json(state.likedIds)),
    http.get(`${BASE}/api/users/me/likes`, () => HttpResponse.json(pageOf(liked))),
    http.delete(`${BASE}/api/users/me/likes/:id`, ({ params }) => {
      state.deletes.push(params.id as string);
      state.likedIds = state.likedIds.filter((x) => x !== params.id);
      return new HttpResponse(null, { status: 204 });
    }),
    http.get(`${BASE}/api/users/me/cookbooks`, () => {
      state.cookbookCalls += 1;
      return HttpResponse.json(cookbooks);
    }),
  );
  return state;
}

const fiveIds = ['a', 'b', 'c', 'd', 'e'];

beforeEach(() => {
  mocks.session = null;
});

describe('the tab', () => {
  test('sitsBetweenSavedAndAiRecipes', async () => {
    signedIn();
    renderAt(TAB);
    const tab = await screen.findByRole('tab', { name: 'Cookbooks' });
    expect(tab).toHaveAttribute('aria-selected', 'true');
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual([
      'My recipes', 'Saved', 'Cookbooks', 'AI recipes', 'Preferences',
    ]);
    expectNoEmoji();
  });

  test('asksForNothingUntilItIsOpened', async () => {
    const state = signedIn();
    renderAt('/me');
    await screen.findByRole('tab', { name: 'Cookbooks' });
    expect(state.cookbookCalls).toBe(0);
  });
});

describe('the Create button and progress', () => {
  test('belowFiveLikesWithNoCookbooks', async () => {
    signedIn({ likedIds: ['a', 'b', 'c'] });
    renderAt(TAB);
    expect(await screen.findByText('3 of 5 likes to make your first cookbook')).toBeInTheDocument();
    const create = screen.getByRole('button', { name: 'Create a cookbook' });
    expect(create).toHaveAttribute('aria-disabled', 'true');
    expect(create).toHaveAccessibleDescription('3 of 5 likes to make your first cookbook');
    expect(screen.queryByRole('link', { name: 'Create a cookbook' })).not.toBeInTheDocument();
  });

  test('belowFiveLikesWithACookbook', async () => {
    signedIn({ likedIds: ['a', 'b', 'c'], cookbooks: [book('b-1', 1)] });
    renderAt(TAB);
    expect(await screen.findByText('3 of 5 likes to make a cookbook')).toBeInTheDocument();
  });

  test('atFiveLikesIsALinkToThePickPage', async () => {
    signedIn({ likedIds: fiveIds });
    renderAt(TAB);
    const create = await screen.findByRole('link', { name: 'Create a cookbook' });
    expect(create).toHaveAttribute('href', '/cookbooks/new');
  });

  test('atTwentyCookbooksIsUnavailable', async () => {
    signedIn({
      likedIds: fiveIds,
      cookbooks: Array.from({ length: 20 }, (_, i) => book(`b-${i}`, i + 1)),
    });
    renderAt(TAB);
    expect(await screen.findByText('You can have up to 20 cookbooks.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create a cookbook' })).toHaveAttribute('aria-disabled', 'true');
    expect(screen.queryByRole('link', { name: 'Create a cookbook' })).not.toBeInTheDocument();
  });
});

describe('the shelf', () => {
  test('thumbnailsShowTheVolumeAndLinkToTheBook', async () => {
    signedIn({ cookbooks: [book('b-2', 2, 'Weeknight dinners'), book('b-1', 1)] });
    renderAt(TAB);
    const second = await screen.findByRole('link', { name: /Volume 2/ });
    expect(second).toHaveAttribute('href', '/cookbooks/b-2');
    expect(within(second).getByText('Weeknight dinners')).toBeInTheDocument();
    expect(within(second).getByText('Jane Doe')).toBeInTheDocument();
    expect(within(screen.getByRole('link', { name: /Volume 1/ })).getByText('Recipes I loved')).toBeInTheDocument();
    expectNoEmoji();
  });

  test('showsAMessageWithNoCookbooks', async () => {
    signedIn();
    renderAt(TAB);
    expect(await screen.findByText(/no cookbooks yet/i)).toBeInTheDocument();
  });
});

describe('the liked recipes', () => {
  test('showsCardsAndStubs', async () => {
    signedIn({
      likedIds: ['a', 'b'],
      liked: [
        likedItem('a', 'Lemon Tart'),
        { kind: 'stub', recipe: null, stub: { id: 'b', approvedTitle: 'Old Stew', state: 'under_review' } },
      ],
    });
    renderAt(TAB);
    expect(await screen.findByRole('heading', { name: 'Lemon Tart' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Old Stew' })).toBeInTheDocument();
    expect(screen.getByText('Under review')).toBeInTheDocument();
  });

  test('showsAMessageWithNoLikes', async () => {
    signedIn();
    renderAt(TAB);
    expect(await screen.findByText(/tap the heart on any recipe to like it/i)).toBeInTheDocument();
  });

  test('unlikingKeepsTheCardWithAnEmptyHeartAndFocus', async () => {
    const state = signedIn({ likedIds: ['a'], liked: [likedItem('a', 'Lemon Tart')] });
    renderAt(TAB);
    const heart = await screen.findByRole('button', { name: /^Remove like from Lemon Tart/ });
    await waitFor(() => expect(heart).toBeEnabled());
    heart.focus();
    await userEvent.keyboard('{Enter}');

    await waitFor(() => expect(state.deletes).toEqual(['a']));
    const after = await screen.findByRole('button', { name: /^Like Lemon Tart, 1 like$/ });
    expect(after).toHaveAttribute('aria-pressed', 'false');
    expect(after).toHaveFocus();
    expect(screen.getByRole('heading', { name: 'Lemon Tart' })).toBeInTheDocument();
  });
});

test('anotherAccountInTheSameTabNeverSeesTheFirstUsersData', async () => {
  const queryClient = newQueryClient();
  signedIn({ likedIds: fiveIds, cookbooks: [book('b-1', 1, 'First users book')], liked: [likedItem('a', 'First users tart')] });
  const first = renderAt(TAB, queryClient);
  await screen.findByText('First users book');
  await screen.findByRole('heading', { name: 'First users tart' });
  await act(async () => first.unmount());

  signedIn({ userId: 'u-2' });
  let open!: () => void;
  const gate = new Promise<void>((resolve) => { open = resolve; });
  const held = async <T,>(body: T) => {
    await gate;
    return HttpResponse.json(body as never);
  };
  server.use(
    http.get(`${BASE}/api/users/me/cookbooks`, () => held([])),
    http.get(`${BASE}/api/users/me/likes`, () => held(pageOf([]))),
    http.get(`${BASE}/api/users/me/likes/ids`, () => held([])),
  );
  renderAt(TAB, queryClient);

  await screen.findByRole('tab', { name: 'Cookbooks' });
  await waitFor(() => expect(queryClient.getQueryCache().find({ queryKey: ['cookbooks', 'u-2'] })).toBeDefined());
  expect(screen.queryByText('First users book')).not.toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: 'First users tart' })).not.toBeInTheDocument();
  open();

  expect(await screen.findByText(/no cookbooks yet/i)).toBeInTheDocument();
  expect(await screen.findByText('0 of 5 likes to make your first cookbook')).toBeInTheDocument();
  expect(screen.queryByText('First users book')).not.toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: 'First users tart' })).not.toBeInTheDocument();
});
