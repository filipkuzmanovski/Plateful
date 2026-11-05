import { http, HttpResponse } from 'msw';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '@/test/server';
import { AuthProvider } from '@/auth/AuthProvider';
import AppRoutes from '@/AppRoutes';
import { pageOf, profileFixture, recipeFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import type { Cookbook, SavedItem } from '@/lib/types';

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
  return <span data-testid="path">{loc.pathname}</span>;
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 30_000 }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/cookbooks/new']}>
          <LocationProbe />
          <AppRoutes />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}

const item = (n: number): SavedItem => ({
  kind: 'recipe', recipe: recipeFixture({ id: `r-${n}`, title: `Dish ${n}` }), stub: null,
});
const items = (count: number) => Array.from({ length: count }, (_, i) => item(i + 1));

/** A fake server for liked recipes, paging by the request's page and size, and for creating a book. */
function fakeServer(start: SavedItem[], opts: { conflict?: string; afterConflict?: SavedItem[] } = {}) {
  const state = { liked: start, bodies: [] as unknown[] };
  mocks.session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/users/me/likes`, ({ request }) => {
      const url = new URL(request.url);
      const page = Number(url.searchParams.get('page') ?? '0');
      const size = Number(url.searchParams.get('size') ?? '12');
      const content = state.liked.slice(page * size, (page + 1) * size);
      return HttpResponse.json(pageOf(content, {
        size, number: page, totalElements: state.liked.length, totalPages: Math.max(1, Math.ceil(state.liked.length / size)),
      }));
    }),
    http.post(`${BASE}/api/users/me/cookbooks`, async ({ request }) => {
      state.bodies.push(await request.json());
      if (opts.conflict) {
        if (opts.afterConflict) state.liked = opts.afterConflict;
        return HttpResponse.json({ status: 409, detail: opts.conflict }, { status: 409 });
      }
      return HttpResponse.json({ id: 'b-9', volume: 1, subtitle: null, createdAt: '2026-11-05T10:00:00Z', recipeCount: 5 }, { status: 201 });
    }),
    http.get(`${BASE}/api/users/me/cookbooks/b-9`, () => HttpResponse.json({
      id: 'b-9', volume: 1, subtitle: null, createdAt: '2026-11-05T10:00:00Z',
      ownerFirstName: 'Jane', ownerLastName: 'Doe', chapters: [],
    } satisfies Cookbook)),
  );
  return state;
}

const box = (title: string) => screen.findByRole('checkbox', { name: new RegExp(`${title}$`) });
const tick = async (...titles: string[]) => {
  for (const title of titles) await userEvent.click(await box(title));
};
const createButton = () => screen.findByRole('button', { name: 'Create cookbook' });

beforeEach(() => {
  mocks.session = null;
});

test('ticksShowChapterNumbersInOrder_andUntickingRenumbers', async () => {
  fakeServer(items(6));
  renderPage();
  await tick('Dish 3', 'Dish 1', 'Dish 2');
  expect(await box('Dish 3')).toHaveAccessibleName('Chapter 1: Dish 3');
  expect(await box('Dish 1')).toHaveAccessibleName('Chapter 2: Dish 1');
  expect(await box('Dish 2')).toHaveAccessibleName('Chapter 3: Dish 2');

  await tick('Dish 3');

  expect(await box('Dish 3')).toHaveAccessibleName('Dish 3');
  expect(await box('Dish 1')).toHaveAccessibleName('Chapter 1: Dish 1');
  expect(await box('Dish 2')).toHaveAccessibleName('Chapter 2: Dish 2');
  expectNoEmoji();
});

test('theLiveLineCountsTheChoice', async () => {
  fakeServer(items(6));
  renderPage();
  await tick('Dish 1', 'Dish 2');
  expect(screen.getByText('2 of 5 to 10 chosen')).toHaveAttribute('aria-live', 'polite');
});

test('createIsUnavailableAtFourAndAvailableAtFive', async () => {
  const state = fakeServer(items(6));
  renderPage();
  await tick('Dish 1', 'Dish 2', 'Dish 3', 'Dish 4');
  const create = await createButton();
  expect(create).toHaveAttribute('aria-disabled', 'true');
  await userEvent.click(create);
  expect(state.bodies).toEqual([]);

  await tick('Dish 5');
  expect(create).not.toHaveAttribute('aria-disabled');
});

test('atTenTheOtherBoxesAreUnavailableAndTheLineSaysWhy', async () => {
  fakeServer(items(12));
  renderPage();
  await tick(...Array.from({ length: 10 }, (_, i) => `Dish ${i + 1}`));

  const eleventh = await box('Dish 11');
  expect(eleventh).toHaveAttribute('aria-disabled', 'true');
  await userEvent.click(eleventh);
  expect(eleventh).not.toBeChecked();
  expect(screen.getByText(/10 of 5 to 10 chosen\. That is the most one cookbook can hold\./)).toBeInTheDocument();

  await tick('Dish 1');
  expect(eleventh).not.toHaveAttribute('aria-disabled');
});

test('theChoiceSurvivesLoadMore', async () => {
  fakeServer(items(14));
  renderPage();
  await tick('Dish 1');
  await userEvent.click(await screen.findByRole('button', { name: 'Load more' }));
  await box('Dish 14');
  expect(await box('Dish 1')).toBeChecked();
  expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
});

test('stubsAreNotOffered', async () => {
  fakeServer([
    ...items(5),
    { kind: 'stub', recipe: null, stub: { id: 's-1', approvedTitle: 'Hidden Stew', state: 'under_review' } },
  ]);
  renderPage();
  await box('Dish 1');
  expect(screen.queryByText('Hidden Stew')).not.toBeInTheDocument();
});

test('saysWhenFewerThanFiveCanBeChosen', async () => {
  fakeServer([
    ...items(4),
    { kind: 'stub', recipe: null, stub: { id: 's-1', approvedTitle: 'Hidden Stew', state: 'unavailable' } },
  ]);
  renderPage();
  expect(await screen.findByText(/Only 4 of your liked recipes are available\. A cookbook needs at least 5\./)).toBeInTheDocument();
});

test('sendsTheIdsInTickOrderAndATrimmedSubtitle', async () => {
  const state = fakeServer(items(6));
  renderPage();
  await tick('Dish 5', 'Dish 4', 'Dish 3', 'Dish 2', 'Dish 1');
  await userEvent.type(screen.getByRole('textbox', { name: /subtitle/i }), '  Weeknight dinners ');
  await userEvent.click(await createButton());

  await waitFor(() => expect(state.bodies).toEqual([
    { recipeIds: ['r-5', 'r-4', 'r-3', 'r-2', 'r-1'], subtitle: 'Weeknight dinners' },
  ]));
});

test('aBlankSubtitleIsLeftOut_andSuccessOpensTheBook', async () => {
  const state = fakeServer(items(5));
  renderPage();
  await tick('Dish 1', 'Dish 2', 'Dish 3', 'Dish 4', 'Dish 5');
  await userEvent.type(screen.getByRole('textbox', { name: /subtitle/i }), '   ');
  await userEvent.click(await createButton());

  await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent('/cookbooks/b-9'));
  expect(state.bodies).toEqual([{ recipeIds: ['r-1', 'r-2', 'r-3', 'r-4', 'r-5'] }]);
});

test('theSubtitleFieldCapsAt80WithAPlaceholder', async () => {
  fakeServer(items(5));
  renderPage();
  await box('Dish 1');
  const field = screen.getByRole('textbox', { name: /subtitle/i });
  expect(field).toHaveAttribute('maxlength', '80');
  expect(field).toHaveAttribute('placeholder', 'Recipes I loved');
});

test('aConflictShowsItsMessage_keepsThePage_andDropsTicksNoLongerOffered', async () => {
  const detail = 'Some of these recipes are no longer available.';
  fakeServer(items(6), { conflict: detail, afterConflict: items(6).slice(1) });
  renderPage();
  await tick('Dish 1', 'Dish 2', 'Dish 3', 'Dish 4', 'Dish 5');
  await userEvent.click(await createButton());

  expect(await screen.findByRole('alert')).toHaveTextContent(detail);
  expect(screen.getByTestId('path')).toHaveTextContent('/cookbooks/new');
  await waitFor(() => expect(screen.queryByRole('checkbox', { name: /Dish 1$/ })).not.toBeInTheDocument());
  expect(await box('Dish 2')).toHaveAccessibleName('Chapter 1: Dish 2');
  expect(screen.getByText('4 of 5 to 10 chosen')).toBeInTheDocument();
});
