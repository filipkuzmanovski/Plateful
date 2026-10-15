import { delay, http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { recipeFixture, pageOf } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';

const BASE = 'http://localhost:8080';

const cuisineCatalogue = [
  { key: 'italian', label: 'Italian', region: 'europe', seas: ['mediterranean'], count: 12 },
  { key: 'japanese', label: 'Japanese', region: 'asia', seas: [], count: 5 },
  { key: 'greek', label: 'Greek', region: 'europe', seas: ['mediterranean'], count: 3 },
  { key: 'macedonian', label: 'Macedonian', region: 'europe', seas: [], count: 1 },
  { key: 'german', label: 'German', region: 'europe', seas: [], count: 0 },
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

const seaCatalogue = [
  { key: 'mediterranean', label: 'Mediterranean', vibe: 'Fresh, olive-oil drenched, citrusy, and simple.', starIngredients: 'Branzino, octopus, sardines, squid, mussels.', iconDishes: 'Spanish Paella, Grilled Branzino, Calamari.' },
  { key: 'caribbean', label: 'Caribbean', vibe: 'Citrus-marinated, spicy, fried, and robust.', starIngredients: 'Spiny lobster, snapper, conch, mahi-mahi.', iconDishes: 'Conch Fritters, Jamaican Escovitch Fish.' },
  { key: 'north_sea', label: 'North Sea', vibe: 'Comforting, smoky, pickled, and hearty.', starIngredients: 'Cod, haddock, herring, langoustines.', iconDishes: 'British Fish & Chips, Scandinavian Pickled Herring.' },
  { key: 'pacific', label: 'Sea of Japan & Pacific', vibe: 'Umami-rich, clean, precise, often raw or lightly seared.', starIngredients: 'Salmon, mackerel, king crab, sea urchin (uni).', iconDishes: 'Sashimi, Miso-Glazed Salmon, Crab Hot Pot.' },
  { key: 'gulf_of_mexico', label: 'Gulf of Mexico', vibe: 'Cajun/Creole spiced, buttery, and deeply comforting.', starIngredients: 'Gulf shrimp, blue crab, oysters, redfish.', iconDishes: "Shrimp Po' Boys, Seafood Gumbo, Oysters Rockefeller." },
];

beforeEach(() => {
  server.use(
    http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json(cuisineCatalogue)),
    http.get(`${BASE}/api/recipes/seas`, () => HttpResponse.json(seaCatalogue)),
  );
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

// ---- seas ----

const group = (drawer: HTMLElement, title: string) =>
  within(drawer).getByRole('heading', { name: title }).closest('section') as HTMLElement;

test('seasSectionShowsFiveChips', async () => {
  captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  const seas = group(drawer, 'Seas');
  const chips = await within(seas).findAllByRole('button');
  expect(chips.map((c) => c.textContent)).toEqual([
    'Mediterranean',
    'Caribbean',
    'North Sea',
    'Sea of Japan & Pacific',
    'Gulf of Mexico',
  ]);
  expect(within(group(drawer, 'Regions')).queryByRole('button', { name: 'Mediterranean' })).not.toBeInTheDocument();
});

test('tickingCaribbeanShowsCardAndFilters', async () => {
  const requests = captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  expect(within(drawer).queryByText('Citrus-marinated, spicy, fried, and robust.')).not.toBeInTheDocument();
  await userEvent.click(await within(group(drawer, 'Seas')).findByRole('button', { name: 'Caribbean' }));
  const card = await within(drawer).findByRole('region', { name: 'Caribbean' });
  expect(within(card).getByText('Citrus-marinated, spicy, fried, and robust.')).toBeVisible();
  expect(within(card).getByText('Vibe')).toBeInTheDocument();
  expect(within(card).getByText('Star ingredients')).toBeInTheDocument();
  expect(within(card).getByText('Icon dishes')).toBeInTheDocument();
  expect(within(card).getByText('Spiny lobster, snapper, conch, mahi-mahi.')).toBeInTheDocument();
  expectNoEmoji();
  await applyDrawer(drawer);
  await vi.waitFor(() => expect(requests.lastGrid().getAll('sea')).toEqual(['caribbean']));
  expect(screen.getByRole('button', { name: 'Remove Caribbean' })).toBeInTheDocument();
});

test('seaCardsFollowSeaOrderNotSelectionOrder', async () => {
  captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  const seas = group(drawer, 'Seas');
  await userEvent.click(await within(seas).findByRole('button', { name: 'Gulf of Mexico' }));
  await userEvent.click(within(seas).getByRole('button', { name: 'Mediterranean' }));
  const cards = await within(drawer).findAllByRole('region', { name: /^(Mediterranean|Gulf of Mexico)$/ });
  expect(cards.map((c) => c.getAttribute('aria-label'))).toEqual(['Mediterranean', 'Gulf of Mexico']);
});

test('urlSeaPreTicksAndActiveChipRemoves', async () => {
  const requests = captureRecipes();
  renderApp('/?sea=north_sea');
  await screen.findByText('Bruschetta Pork & Pasta');
  expect(requests.lastGrid().getAll('sea')).toEqual(['north_sea']);
  const drawer = await openDrawer();
  expect(await within(group(drawer, 'Seas')).findByRole('button', { name: 'North Sea' })).toHaveAttribute('aria-pressed', 'true');
  await userEvent.keyboard('{Escape}');
  await vi.waitFor(() => expect(screen.queryByRole('dialog', { name: 'Filters' })).not.toBeInTheDocument());
  await userEvent.click(screen.getByRole('button', { name: 'Remove North Sea' }));
  await vi.waitFor(() => expect(requests.lastGrid().has('sea')).toBe(false));
});

test('seasEndpointFailureFallsBack', async () => {
  server.use(http.get(`${BASE}/api/recipes/seas`, () => HttpResponse.json({ detail: 'boom' }, { status: 500 })));
  const requests = captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  const drawer = await openDrawer();
  const seas = group(drawer, 'Seas');
  expect(await within(seas).findAllByRole('button')).toHaveLength(5);
  await userEvent.click(within(seas).getByRole('button', { name: 'Gulf of Mexico' }));
  // Let the failed query settle, then confirm no card and no error banner.
  await new Promise((r) => setTimeout(r, 50));
  expect(within(drawer).queryByRole('region', { name: 'Gulf of Mexico' })).not.toBeInTheDocument();
  expect(within(drawer).queryByRole('alert')).not.toBeInTheDocument();
  await applyDrawer(drawer);
  await vi.waitFor(() => expect(requests.lastGrid().getAll('sea')).toEqual(['gulf_of_mexico']));
});

// ---- sorting ----

const sortTrigger = () => screen.getByRole('combobox', { name: /^Sort by/ });

test('sortSelectOffersTheFourOrdersInOrder', async () => {
  captureRecipes();
  renderApp('/');
  await screen.findByText('Bruschetta Pork & Pasta');
  await userEvent.click(sortTrigger());
  const menu = await screen.findByRole('listbox');
  expect(within(menu).getAllByRole('option').map((o) => o.textContent)).toEqual([
    'Newest',
    'Most rated',
    'Most popular',
    'Most comments',
  ]);
  expect(within(menu).getByRole('option', { name: 'Newest' })).toHaveAttribute('aria-selected', 'true');
  expectNoEmoji();
});

test('sortSelectChangesUrlAndRequest', async () => {
  const requests = captureRecipes(pageOf([recipeFixture()], { totalPages: 3, number: 2 }));
  renderApp('/?page=2');
  await screen.findByText('Bruschetta Pork & Pasta');
  expect(requests.lastGrid().get('page')).toBe('2');
  await userEvent.click(sortTrigger());
  await userEvent.click(await screen.findByRole('option', { name: 'Most rated' }));
  await vi.waitFor(() => expect(requests.lastGrid().get('sortBy')).toBe('most-rated'));
  expect(requests.lastGrid().get('page')).toBe('0');
  expect(sortTrigger()).toHaveTextContent('Sort: Most rated');
  expect(sortTrigger()).toHaveAccessibleName('Sort by: Most rated');
});

test('applyingFiltersKeepsSort', async () => {
  const requests = captureRecipes();
  renderApp('/?sort=top-rated');
  await screen.findByText('Bruschetta Pork & Pasta');
  expect(sortTrigger()).toHaveTextContent('Sort: Most popular');
  const drawer = await openDrawer();
  await userEvent.click(within(drawer).getByRole('button', { name: 'Vegan' }));
  await applyDrawer(drawer);
  await vi.waitFor(() => expect(requests.lastGrid().get('vegan')).toBe('true'));
  expect(requests.lastGrid().get('sortBy')).toBe('top-rated');
});

test('bogusSortShowsNewest', async () => {
  const requests = captureRecipes();
  renderApp('/?sort=bogus');
  await screen.findByText('Bruschetta Pork & Pasta');
  expect(sortTrigger()).toHaveTextContent('Sort: Newest');
  expect(sortTrigger()).toHaveAccessibleName('Sort by: Newest');
  expect(requests.lastGrid().get('sortBy')).toBe('newest');
});
