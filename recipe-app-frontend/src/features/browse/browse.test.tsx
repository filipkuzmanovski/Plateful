import { delay, http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, pageOf } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';

const BASE = 'http://localhost:8080';

const cuisineCatalogue = [
  { key: 'italian', label: 'Italian', region: 'europe', mediterranean: true, count: 12 },
  { key: 'japanese', label: 'Japanese', region: 'asia', mediterranean: false, count: 5 },
  { key: 'greek', label: 'Greek', region: 'europe', mediterranean: true, count: 3 },
  { key: 'macedonian', label: 'Macedonian', region: 'europe', mediterranean: false, count: 1 },
  { key: 'german', label: 'German', region: 'europe', mediterranean: false, count: 0 },
];

/** Every /api/recipes request, split into grid requests (size=12) and live-count requests (size=1). */
interface Requests {
  grid: URLSearchParams[];
  count: URLSearchParams[];
  lastGrid: () => URLSearchParams;
}

function captureRecipes(body = pageOf([recipeFixture()]), countTotal = 7): Requests {
  const store: Requests = {
    grid: [],
    count: [],
    lastGrid: () => store.grid[store.grid.length - 1],
  };
  server.use(
    http.get(`${BASE}/api/recipes`, ({ request }) => {
      const params = new URL(request.url).searchParams;
      if (params.get('size') === '1') {
        store.count.push(params);
        return HttpResponse.json(pageOf([recipeFixture()], { size: 1, totalElements: countTotal }));
      }
      store.grid.push(params);
      return HttpResponse.json(body);
    }),
  );
  return store;
}

beforeEach(() => {
  server.use(http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json(cuisineCatalogue)));
});

async function openDrawer() {
  await userEvent.click(screen.getByRole('button', { name: 'Filters' }));
  return screen.findByRole('dialog', { name: 'Filters' });
}

async function applyDrawer(drawer: HTMLElement) {
  await userEvent.click(within(drawer).getByRole('button', { name: /^show (\d+ )?recipes?$/i }));
}

// ---- non-filter behaviour (kept) ----

test('renders recipe cards from the API', async () => {
  captureRecipes();
  renderApp('/');
  expect(await screen.findByText('Bruschetta Pork & Pasta')).toBeInTheDocument();
  expect(screen.getByText(/4\.6/)).toBeInTheDocument();
  expect(screen.getByText(/35 min/i)).toBeInTheDocument();
});

test('filters initialize from the URL', async () => {
  const requests = captureRecipes();
  renderApp('/?cuisine=thai&maxReadyInMinutes=30&page=1');
  await screen.findByText('Bruschetta Pork & Pasta');
  const params = requests.lastGrid();
  expect(params.get('cuisine')).toBe('thai');
  expect(params.get('maxReadyInMinutes')).toBe('30');
  expect(params.get('page')).toBe('1');
});

test('empty result shows the empty state', async () => {
  captureRecipes(pageOf([]));
  renderApp('/');
  expect(await screen.findByText(/no recipes match/i)).toBeInTheDocument();
});

test('pagination renders page buttons from totalPages', async () => {
  captureRecipes(pageOf([recipeFixture()], { totalPages: 3, number: 0 }));
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  expect(screen.getByRole('button', { name: 'Go to page 3' })).toBeInTheDocument();
});

test('a garbage page param falls back to page 0', async () => {
  const requests = captureRecipes();
  renderApp('/?page=abc');
  await screen.findByText('Bruschetta Pork & Pasta');
  expect(requests.lastGrid().get('page')).toBe('0');
});

test('while new results load, the old grid is marked busy', async () => {
  let gridCalls = 0;
  server.use(
    http.get(`${BASE}/api/recipes`, async ({ request }) => {
      if (new URL(request.url).searchParams.get('size') === '1') {
        return HttpResponse.json(pageOf([recipeFixture()], { size: 1, totalElements: 7 }));
      }
      gridCalls += 1;
      if (gridCalls > 1) await delay(150);
      return HttpResponse.json(pageOf([recipeFixture()]));
    }),
  );
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  await userEvent.click(within(drawer).getByRole('button', { name: 'Vegan' }));
  await applyDrawer(drawer);
  await vi.waitFor(() =>
    expect(screen.getByText('Bruschetta Pork & Pasta').closest('[aria-busy]')).toHaveAttribute('aria-busy', 'true'),
  );
  await vi.waitFor(() =>
    expect(screen.getByText('Bruschetta Pork & Pasta').closest('[aria-busy]')).toHaveAttribute('aria-busy', 'false'),
  );
});

test('browse renders without emoji characters', async () => {
  captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  expectNoEmoji();
});

// ---- filter drawer ----

test('opensAndClosesWithEscape', async () => {
  captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  expect(drawer).toBeVisible();
  await userEvent.keyboard('{Escape}');
  await vi.waitFor(() => expect(screen.queryByRole('dialog', { name: 'Filters' })).not.toBeInTheDocument());
  expect(screen.getByRole('button', { name: 'Filters' })).toHaveFocus();
});

test('draftChangesDoNotTouchUrlUntilApply', async () => {
  const requests = captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  await userEvent.click(await within(drawer).findByRole('button', { name: /italian/i }));
  expect(within(drawer).getByRole('button', { name: /italian/i })).toHaveAttribute('aria-pressed', 'true');
  expect(requests.lastGrid().has('cuisine')).toBe(false);

  await applyDrawer(drawer);
  await vi.waitFor(() => expect(requests.lastGrid().getAll('cuisine')).toEqual(['italian']));
  expect(requests.lastGrid().get('page')).toBe('0');
  expect(screen.queryByRole('dialog', { name: 'Filters' })).not.toBeInTheDocument();
});

test('closingWithoutApplyDiscardsDraft', async () => {
  const requests = captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  let drawer = await openDrawer();
  await userEvent.click(await within(drawer).findByRole('button', { name: /greek/i }));
  expect(within(drawer).getByRole('button', { name: /greek/i })).toHaveAttribute('aria-pressed', 'true');
  await userEvent.keyboard('{Escape}');
  await vi.waitFor(() => expect(screen.queryByRole('dialog', { name: 'Filters' })).not.toBeInTheDocument());

  drawer = await openDrawer();
  expect(await within(drawer).findByRole('button', { name: /greek/i })).toHaveAttribute('aria-pressed', 'false');
  expect(requests.grid.length).toBeGreaterThan(0);
  expect(requests.grid.some((p) => p.getAll('cuisine').includes('greek'))).toBe(false);
  expect(screen.queryByRole('button', { name: 'Remove Greek' })).not.toBeInTheDocument();
});

test('liveCountLabel', async () => {
  captureRecipes(pageOf([recipeFixture()]), 7);
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  expect(await within(drawer).findByRole('button', { name: 'Show 7 recipes' })).toBeInTheDocument();
});

test('regionChipSendsRegionParam', async () => {
  const requests = captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  await userEvent.click(within(drawer).getByRole('button', { name: 'Europe' }));
  await applyDrawer(drawer);
  await vi.waitFor(() => expect(requests.lastGrid().getAll('region')).toEqual(['europe']));
  expect(screen.getByRole('button', { name: 'Remove Europe' })).toBeInTheDocument();
});

test('azShowsOnlyCuisinesWithRecipesAndSearches', async () => {
  const requests = captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  await userEvent.click(await within(drawer).findByRole('button', { name: /all cuisines/i }));

  expect(within(drawer).getByRole('checkbox', { name: 'Italian' })).toBeInTheDocument();
  expect(within(drawer).getByRole('checkbox', { name: 'Macedonian' })).toBeInTheDocument();
  expect(within(drawer).queryByRole('checkbox', { name: 'German' })).not.toBeInTheDocument();

  await userEvent.type(within(drawer).getByLabelText('Search cuisines'), 'mac');
  const boxes = within(drawer).getAllByRole('checkbox');
  expect(boxes).toHaveLength(1);
  expect(boxes[0]).toHaveAccessibleName('Macedonian');

  await userEvent.click(boxes[0]);
  await userEvent.click(within(drawer).getByRole('button', { name: 'Back' }));
  // The tick is reflected back on the main view.
  expect(within(drawer).getByRole('button', { name: /macedonian/i })).toHaveAttribute('aria-pressed', 'true');
  await applyDrawer(drawer);
  await vi.waitFor(() => expect(requests.lastGrid().getAll('cuisine')).toEqual(['macedonian']));
});

test('invalidTypedTimeIsExcluded', async () => {
  const requests = captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  await userEvent.type(within(drawer).getByLabelText('Max time (minutes)'), '900');
  expect(within(drawer).getByRole('alert')).toBeInTheDocument();
  await userEvent.click(within(drawer).getByRole('button', { name: 'Vegan' }));
  await applyDrawer(drawer);
  await vi.waitFor(() => expect(requests.lastGrid().get('vegan')).toBe('true'));
  expect(requests.lastGrid().has('maxReadyInMinutes')).toBe(false);
});

test('a valid typed time and rating are applied', async () => {
  const requests = captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  await userEvent.type(within(drawer).getByLabelText('Max time (minutes)'), '45');
  await userEvent.type(within(drawer).getByLabelText('Min rating'), '4.5');
  expect(within(drawer).queryByRole('alert')).not.toBeInTheDocument();
  await applyDrawer(drawer);
  await vi.waitFor(() => expect(requests.lastGrid().get('maxReadyInMinutes')).toBe('45'));
  expect(requests.lastGrid().get('minRating')).toBe('4.5');
});

test('unset filters are never sent, and applying from a later page resets to page 0', async () => {
  const requests = captureRecipes(pageOf([recipeFixture()], { totalPages: 3, number: 2 }));
  renderApp('/?page=2');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  await userEvent.click(within(drawer).getByRole('button', { name: 'Vegetarian' }));
  await applyDrawer(drawer);
  await vi.waitFor(() => {
    const params = requests.lastGrid();
    expect(params.get('vegetarian')).toBe('true');
    expect(params.get('page')).toBe('0');
    for (const unset of ['cuisine', 'region', 'vegan', 'glutenFree', 'dairyFree', 'maxReadyInMinutes', 'minRating']) {
      expect(params.has(unset)).toBe(false);
    }
  });
  expect(screen.getByRole('button', { name: 'Remove Vegetarian' })).toBeInTheDocument();
});

test('clear all resets the draft', async () => {
  const requests = captureRecipes();
  renderApp('/?vegan=true&region=asia');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  expect(within(drawer).getByRole('button', { name: 'Vegan' })).toHaveAttribute('aria-pressed', 'true');
  await userEvent.click(within(drawer).getByRole('button', { name: 'Clear all' }));
  expect(within(drawer).getByRole('button', { name: 'Vegan' })).toHaveAttribute('aria-pressed', 'false');
  expect(within(drawer).getByRole('button', { name: 'Asia' })).toHaveAttribute('aria-pressed', 'false');
  await applyDrawer(drawer);
  await vi.waitFor(() => {
    expect(requests.lastGrid().has('vegan')).toBe(false);
    expect(requests.lastGrid().has('region')).toBe(false);
  });
});

test('activeChipRemovesFilter', async () => {
  const requests = captureRecipes();
  renderApp('/?cuisine=italian&vegan=true');
  await screen.findByText('Bruschetta Pork & Pasta');
  expect(screen.getByRole('button', { name: 'Filters' })).toHaveTextContent('2');
  await userEvent.click(await screen.findByRole('button', { name: 'Remove Italian' }));
  await vi.waitFor(() => {
    const params = requests.lastGrid();
    expect(params.get('vegan')).toBe('true');
    expect(params.has('cuisine')).toBe(false);
  });
  expect(screen.queryByRole('button', { name: 'Remove Italian' })).not.toBeInTheDocument();
});

test('cuisinesErrorStillAllowsDietFilter', async () => {
  server.use(http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json({ detail: 'boom' }, { status: 500 })));
  const requests = captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  expect(await within(drawer).findByText("Couldn't load cuisines")).toBeInTheDocument();
  expect(within(drawer).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  await userEvent.click(within(drawer).getByRole('button', { name: 'Vegan' }));
  await applyDrawer(drawer);
  await vi.waitFor(() => expect(requests.lastGrid().get('vegan')).toBe('true'));
});

test('the open drawer and its A–Z view render without emoji characters', async () => {
  captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  await within(drawer).findByRole('button', { name: /italian/i });
  expectNoEmoji();
  await userEvent.click(within(drawer).getByRole('button', { name: /all cuisines/i }));
  await within(drawer).findByRole('checkbox', { name: 'Italian' });
  expectNoEmoji();
});
