import { http, HttpResponse } from 'msw';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { toast } from 'sonner';
import { server } from '@/test/server';
import { AuthProvider } from '@/auth/AuthProvider';
import AppRoutes from '@/AppRoutes';
import { pageOf, profileFixture, recipeFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import type { RecipeDisplay } from '@/lib/types';

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

vi.mock('sonner', async (original) => ({
  ...(await original<typeof import('sonner')>()),
  toast: { error: vi.fn(), success: vi.fn() },
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
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/users/me/saved/ids`, () => HttpResponse.json([])),
  );
}

function fakeLikes(ids: string[] = [], opts: { putGate?: Promise<void>; putFails?: boolean } = {}) {
  const state = { ids: [...ids], puts: [] as string[], deletes: [] as string[] };
  server.use(
    http.get(`${BASE}/api/users/me/likes/ids`, () => HttpResponse.json([...state.ids])),
    http.put(`${BASE}/api/users/me/likes/:id`, async ({ params }) => {
      state.puts.push(params.id as string);
      if (opts.putGate) await opts.putGate;
      if (opts.putFails) return HttpResponse.json({ status: 500, detail: 'no' }, { status: 500 });
      state.ids.push(params.id as string);
      return new HttpResponse(null, { status: 204 });
    }),
    http.delete(`${BASE}/api/users/me/likes/:id`, ({ params }) => {
      state.deletes.push(params.id as string);
      state.ids = state.ids.filter((x) => x !== params.id);
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return state;
}

function stubBrowse(recipe: RecipeDisplay) {
  server.use(
    http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json([])),
    http.get(`${BASE}/api/recipes/seas`, () => HttpResponse.json([])),
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([recipe]))),
  );
}

function stubDetail(recipe: RecipeDisplay) {
  server.use(
    http.get(`${BASE}/api/recipes/r-1`, () => HttpResponse.json(recipe)),
    http.get(`${BASE}/api/recipes/r-1/comments`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/recipes/r-1/comments/reactions/me`, () => HttpResponse.json([])),
    http.get(`${BASE}/api/recipes/r-1/ratings/me`, () =>
      HttpResponse.json({ status: 404, detail: 'none' }, { status: 404 }),
    ),
  );
}

const TITLE = 'Bruschetta Pork & Pasta';
const heart = (name: RegExp) => screen.findByRole('button', { name });

beforeEach(() => {
  mocks.session = null;
  vi.mocked(toast.error).mockClear();
});

describe('on a card', () => {
  test('showsTheCountInItsName', async () => {
    signedIn();
    fakeLikes();
    stubBrowse(recipeFixture({ likeCount: 28 }));
    renderAt('/');
    const button = await heart(/^Like Bruschetta Pork & Pasta, 28 likes$/);
    expect(button).toHaveTextContent('28');
    expectNoEmoji();
  });

  test('usesTheSingularForOneLike', async () => {
    signedIn();
    fakeLikes();
    stubBrowse(recipeFixture({ likeCount: 1 }));
    renderAt('/');
    await heart(/^Like Bruschetta Pork & Pasta, 1 like$/);
  });

  test('likingSendsPutAndCountsAtOnce', async () => {
    signedIn();
    const state = fakeLikes();
    stubBrowse(recipeFixture({ likeCount: 28 }));
    renderAt('/');
    await waitFor(async () => expect(await heart(/^Like /)).toBeEnabled());
    await userEvent.click(await heart(/^Like /));

    const pressed = await heart(/^Remove like from Bruschetta Pork & Pasta, 29 likes$/);
    expect(pressed).toHaveAttribute('aria-pressed', 'true');
    await waitFor(() => expect(state.puts).toEqual(['r-1']));
    expect(screen.getByTestId('loc')).toHaveTextContent('/');
  });

  test('unlikingSendsDeleteAndCountsDown', async () => {
    signedIn();
    const state = fakeLikes(['r-1']);
    stubBrowse(recipeFixture({ likeCount: 29 }));
    renderAt('/');
    await userEvent.click(await heart(/^Remove like from /));

    await heart(/^Like Bruschetta Pork & Pasta, 28 likes$/);
    await waitFor(() => expect(state.deletes).toEqual(['r-1']));
  });

  test('aFailedLikeGoesBackAndShowsAToast', async () => {
    signedIn();
    fakeLikes([], { putFails: true });
    stubBrowse(recipeFixture({ likeCount: 28 }));
    renderAt('/');
    await waitFor(async () => expect(await heart(/^Like /)).toBeEnabled());
    await userEvent.click(await heart(/^Like /));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith("Couldn't update your like. Please try again."));
    const back = await heart(/^Like Bruschetta Pork & Pasta, 28 likes$/);
    expect(back).toHaveAttribute('aria-pressed', 'false');
  });

  test('twoRapidClicksSendOneRequestAndCountOnce', async () => {
    signedIn();
    let open!: () => void;
    const putGate = new Promise<void>((resolve) => { open = resolve; });
    const state = fakeLikes([], { putGate });
    stubBrowse(recipeFixture({ likeCount: 28 }));
    renderAt('/');
    await waitFor(async () => expect(await heart(/^Like /)).toBeEnabled());
    const button = await heart(/^Like /);
    await userEvent.dblClick(button);

    await heart(/, 29 likes$/);
    expect(screen.getByRole('button', { name: /, 29 likes$/ })).toHaveAttribute('aria-disabled', 'true');
    open();
    await waitFor(() => expect(screen.getByRole('button', { name: /, 29 likes$/ })).not.toHaveAttribute('aria-disabled'));
    expect(state.puts).toEqual(['r-1']);
    expect(state.deletes).toEqual([]);
  });

  test('signedOutClickGoesToSignIn', async () => {
    stubBrowse(recipeFixture({ likeCount: 3 }));
    renderAt('/');
    await userEvent.click(await heart(/^Like /));
    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('/login'));
  });

  test('isNotShownOnAPendingRecipe', async () => {
    signedIn();
    fakeLikes();
    stubBrowse(recipeFixture({ review: { status: 'pending' } as RecipeDisplay['review'] }));
    renderAt('/');
    await screen.findByRole('heading', { name: TITLE });
    expect(screen.queryByRole('button', { name: /^Like /})).not.toBeInTheDocument();
  });

  test('isShownWhenThereIsNoReviewBlock', async () => {
    stubBrowse(recipeFixture({ review: null }));
    renderAt('/');
    await heart(/^Like /);
  });
});

describe('on the recipe page', () => {
  test('showsAHeartBesideSave', async () => {
    signedIn();
    const state = fakeLikes();
    stubDetail(recipeFixture({ likeCount: 4 }));
    renderAt('/recipes/r-1');
    await screen.findByRole('button', { name: /^Save$/ });
    await waitFor(async () => expect(await heart(/^Like Bruschetta Pork & Pasta, 4 likes$/)).toBeEnabled());
    await userEvent.click(await heart(/^Like /));

    await heart(/^Remove like from Bruschetta Pork & Pasta, 5 likes$/);
    await waitFor(() => expect(state.puts).toEqual(['r-1']));
    expectNoEmoji();
  });
});
