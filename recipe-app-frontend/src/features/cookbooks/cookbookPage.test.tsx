import { http, HttpResponse } from 'msw';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation, useNavigate } from 'react-router';
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
  const navigate = useNavigate();
  return (
    <>
      <span data-testid="url">{loc.pathname + loc.search}</span>
      <button onClick={() => navigate(-1)}>history back</button>
    </>
  );
}

function renderBook(search = '') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 30_000 }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[`/cookbooks/b-1${search}`]}>
          <LocationProbe />
          <AppRoutes />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}

const lemon = recipeFixture({
  id: 'r-1', title: 'Lemon Tart', image: 'https://cdn.example.com/lemon.jpg', cuisines: ['italian'], readyInMinutes: 60,
  user: { id: 'u-9', firstName: 'Mira', lastName: 'Stone' }, source: 'user', sourceName: null,
  ingredients: [{ ingredientName: 'lemon', originalText: '2 lemons, juiced' }],
  instructionsSteps: [{ number: 1, step: 'Bake the tart.', ingredients: [], equipment: [] }],
});
const stew = recipeFixture({
  id: 'r-2', title: 'Plain Stew', image: null, cuisines: ['greek'], readyInMinutes: 35, user: null,
  sourceName: 'Pink When', sourceUrl: 'https://example.com/r', instructionsSteps: [], instructions: 'Simmer it slowly.',
});
const asItem = (recipe: typeof lemon): SavedItem => ({ kind: 'recipe', recipe, stub: null });
const reviewStub: SavedItem = { kind: 'stub', recipe: null, stub: { id: 's-1', approvedTitle: 'Old Soup', state: 'under_review' } };
const goneStub: SavedItem = { kind: 'stub', recipe: null, stub: { id: 's-2', approvedTitle: 'Lost Pie', state: 'unavailable' } };

const bookOf = (chapters: SavedItem[], overrides: Partial<Cookbook> = {}): Cookbook => ({
  id: 'b-1', volume: 2, subtitle: null, createdAt: '2026-07-14T10:00:00Z',
  ownerFirstName: 'Jane', ownerLastName: 'Doe', chapters, ...overrides,
});

function fakeServer(book: Cookbook | null) {
  const state = { deletes: 0 };
  mocks.session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json([
      { key: 'italian', label: 'Italian' }, { key: 'greek', label: 'Greek' },
    ])),
    http.get(`${BASE}/api/users/me/cookbooks/b-1`, () =>
      book ? HttpResponse.json(book) : HttpResponse.json({ status: 404, detail: 'Cookbook not found' }, { status: 404 }),
    ),
    http.delete(`${BASE}/api/users/me/cookbooks/b-1`, () => {
      state.deletes += 1;
      return new HttpResponse(null, { status: 204 });
    }),
    http.get(`${BASE}/api/users/me/cookbooks`, () => HttpResponse.json([])),
    http.get(`${BASE}/api/users/me/likes`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/users/u-1/recipes`, () => HttpResponse.json(pageOf([]))),
    http.get(`${BASE}/api/users/me/saved/ids`, () => HttpResponse.json([])),
  );
  return state;
}

/** The leaf on screen. jsdom has no Tailwind, so `hidden` on the others cannot be seen by role queries. */
const activeLeaf = () => document.querySelector<HTMLElement>('[data-leaf][data-active="true"]')!;
const cover = () => screen.findByText('The cookbook of');
const next = () => screen.getByRole('button', { name: 'Next page' });
const previous = () => screen.getByRole('button', { name: 'Previous page' });
const url = () => screen.getByTestId('url').textContent;

beforeEach(() => {
  mocks.session = null;
});

describe('the cover', () => {
  test('showsTheOwnerVolumeAndDefaultSubtitle', async () => {
    fakeServer(bookOf([asItem(lemon)]));
    renderBook();
    await cover();
    const leaf = within(activeLeaf());
    expect(leaf.getByRole('heading', { level: 1 })).toHaveTextContent(/The cookbook of\s*Jane Doe/);
    expect(leaf.getByText('Volume 2')).toBeInTheDocument();
    expect(leaf.getByText('Recipes I loved')).toBeInTheDocument();
    expectNoEmoji();
  });

  test('showsTheBooksOwnSubtitle', async () => {
    fakeServer(bookOf([asItem(lemon)], { subtitle: 'Weeknight dinners' }));
    renderBook();
    await cover();
    expect(within(activeLeaf()).getByText('Weeknight dinners')).toBeInTheDocument();
  });
});

describe('the opening', () => {
  test('showsTheCollectorFactsAndContents', async () => {
    fakeServer(bookOf([asItem(lemon), asItem(stew), reviewStub]));
    renderBook('?page=2');
    await cover();
    const leaf = within(activeLeaf());
    expect(leaf.getByRole('heading', { name: 'Collected by Jane, July 2026' })).toBeInTheDocument();
    await waitFor(() => expect(leaf.getByText(/Italian, Greek/)).toBeInTheDocument());
    expect(leaf.getByText(/1 h 35 min/)).toBeInTheDocument();
    expect(leaf.getByRole('link', { name: /Lemon Tart/ })).toHaveAttribute('href', '/cookbooks/b-1?page=3');
    expect(leaf.getByRole('link', { name: /Plain Stew/ })).toHaveAttribute('href', '/cookbooks/b-1?page=4');
    expect(leaf.getByRole('link', { name: /Old Soup/ })).toHaveAttribute('href', '/cookbooks/b-1?page=5');
  });

  test('allStubsLeaveOutTheFactsButKeepTheContents', async () => {
    fakeServer(bookOf([reviewStub, goneStub]));
    renderBook('?page=2');
    await cover();
    const leaf = within(activeLeaf());
    expect(leaf.getByRole('link', { name: /Old Soup/ })).toBeInTheDocument();
    expect(leaf.getByRole('link', { name: /Lost Pie/ })).toBeInTheDocument();
    expect(leaf.queryByText(/Cuisines/)).not.toBeInTheDocument();
    expect(leaf.queryByText(/Total time/)).not.toBeInTheDocument();
  });
});

describe('a chapter', () => {
  test('showsTheWholeRecipe', async () => {
    fakeServer(bookOf([asItem(lemon)]));
    renderBook('?page=3');
    await cover();
    const leaf = within(activeLeaf());
    expect(leaf.getByText('Chapter 1')).toBeInTheDocument();
    expect(leaf.getByRole('heading', { level: 2, name: 'Lemon Tart' })).toBeInTheDocument();
    expect(leaf.getByRole('link', { name: 'by Mira Stone' })).toHaveAttribute('href', '/users/u-9');
    expect(leaf.getByText(/2 lemons, juiced/)).toBeInTheDocument();
    expect(leaf.getByText('Bake the tart.')).toBeInTheDocument();
    expect(leaf.getByRole('link', { name: /open the recipe page/i })).toHaveAttribute('href', '/recipes/r-1');
    const photo = activeLeaf().querySelector('img')!;
    expect(photo).toHaveAttribute('src', 'https://cdn.example.com/lemon.jpg');
    expect(photo).not.toHaveAttribute('loading', 'lazy');
    expectNoEmoji();
  });

  test('anImportedRecipeCreditsItsSource_andFallsBackToInstructionsText', async () => {
    fakeServer(bookOf([asItem(stew)]));
    renderBook('?page=3');
    await cover();
    const leaf = within(activeLeaf());
    expect(leaf.getByText('From Pink When')).toBeInTheDocument();
    expect(leaf.getByText('Simmer it slowly.')).toBeInTheDocument();
  });

  test('noPhotoShowsTheTitleOnATint', async () => {
    fakeServer(bookOf([asItem(stew)]));
    renderBook('?page=3');
    await cover();
    expect(activeLeaf().querySelector('img')).toBeNull();
    expect(within(activeLeaf()).getAllByText('Plain Stew')).toHaveLength(2);
  });

  test('aStubUnderReviewSaysSo', async () => {
    fakeServer(bookOf([reviewStub]));
    renderBook('?page=3');
    await cover();
    const leaf = within(activeLeaf());
    expect(leaf.getByText('Chapter 1')).toBeInTheDocument();
    expect(leaf.getByRole('heading', { level: 2, name: 'Old Soup' })).toBeInTheDocument();
    expect(leaf.getByText(/being reviewed/i)).toBeInTheDocument();
  });

  test('aGoneStubSaysSo', async () => {
    fakeServer(bookOf([goneStub]));
    renderBook('?page=3');
    await cover();
    expect(within(activeLeaf()).getByText(/no longer available/i)).toBeInTheDocument();
  });
});

describe('turning pages', () => {
  test('nextAndPreviousUpdateTheUrl_andBackReturns', async () => {
    fakeServer(bookOf([asItem(lemon), asItem(stew)]));
    renderBook();
    await cover();
    expect(screen.getByText('Page 1 of 4')).toBeInTheDocument();

    await userEvent.click(next());
    expect(url()).toBe('/cookbooks/b-1?page=2');
    await userEvent.click(next());
    expect(url()).toBe('/cookbooks/b-1?page=3');
    expect(screen.getByText('Page 3 of 4')).toBeInTheDocument();
    await userEvent.click(previous());
    expect(url()).toBe('/cookbooks/b-1?page=2');

    await userEvent.click(screen.getByRole('button', { name: 'history back' }));
    expect(url()).toBe('/cookbooks/b-1?page=3');
    expect(within(activeLeaf()).getByRole('heading', { level: 2, name: 'Lemon Tart' })).toBeInTheDocument();
  });

  test('theEndsDoNotWrap', async () => {
    fakeServer(bookOf([asItem(lemon)]));
    renderBook();
    await cover();
    expect(previous()).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(previous());
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();

    await userEvent.click(next());
    await userEvent.click(next());
    expect(next()).toHaveAttribute('aria-disabled', 'true');
    await userEvent.keyboard('{ArrowRight}');
    expect(screen.getByText('Page 3 of 3')).toBeInTheDocument();
  });

  test('turningMovesFocusToTheNewLeafsHeading', async () => {
    fakeServer(bookOf([asItem(lemon)]));
    renderBook();
    await cover();
    expect(document.activeElement).not.toHaveAttribute('data-leaf-heading');

    await userEvent.click(next());

    expect(within(activeLeaf()).getByRole('heading', { name: 'Collected by Jane, July 2026' })).toHaveFocus();
  });

  test('arrowKeysTurnPages', async () => {
    fakeServer(bookOf([asItem(lemon)]));
    renderBook();
    await cover();
    await userEvent.keyboard('{ArrowRight}');
    expect(screen.getByText('Page 2 of 3')).toBeInTheDocument();
    await userEvent.keyboard('{ArrowLeft}');
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
  });

  test('arrowKeysDoNothingWithAModifierHeld', async () => {
    fakeServer(bookOf([asItem(lemon)]));
    renderBook();
    await cover();
    await userEvent.keyboard('{Alt>}{ArrowRight}{/Alt}');
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
  });

  test('arrowKeysDoNothingWhileTheDeleteDialogIsOpen', async () => {
    fakeServer(bookOf([asItem(lemon)]));
    renderBook();
    await cover();
    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));
    await screen.findByRole('dialog');
    await userEvent.keyboard('{ArrowRight}');
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
  });

  test('arrowKeysDoNothingWhenFocusIsInAField', async () => {
    fakeServer(bookOf([asItem(lemon)]));
    renderBook();
    await cover();
    const field = document.createElement('input');
    document.body.appendChild(field);
    field.focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
    field.remove();
  });

  test.each(['abc', '0', '-1', '99', '1.5'])('page=%s shows the cover', async (raw) => {
    fakeServer(bookOf([asItem(lemon)]));
    renderBook(`?page=${raw}`);
    await cover();
    expect(activeLeaf()).toHaveAttribute('data-leaf', '1');
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
  });

  test('allLeavesAreInThePage_withTheOthersHidden', async () => {
    fakeServer(bookOf([asItem(lemon), asItem(stew)]));
    renderBook('?page=3');
    await cover();
    const leaves = [...document.querySelectorAll('[data-leaf]')];
    expect(leaves).toHaveLength(4);
    expect(leaves.map((l) => l.classList.contains('hidden'))).toEqual([true, true, false, true]);
  });
});

describe('an empty book', () => {
  test('isTheCoverWithAMessageAndDelete', async () => {
    fakeServer(bookOf([]));
    renderBook();
    await cover();
    expect(screen.getByText(/recipes in this book are gone/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Next page' })).not.toBeInTheDocument();
    expect(screen.queryByText(/^Page \d/)).not.toBeInTheDocument();
  });
});

describe('deleting', () => {
  test('confirmsDeletesAndReturnsToTheTab', async () => {
    const state = fakeServer(bookOf([asItem(lemon)]));
    renderBook();
    await cover();
    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete cookbook' }));

    await waitFor(() => expect(url()).toBe('/me?tab=cookbooks'));
    expect(state.deletes).toBe(1);
  });

  test('cancellingDeletesNothing', async () => {
    const state = fakeServer(bookOf([asItem(lemon)]));
    renderBook();
    await cover();
    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(state.deletes).toBe(0);
    expect(url()).toBe('/cookbooks/b-1');
  });
});

describe('printing', () => {
  test('thePrintButtonOpensThePrintDialog', async () => {
    const print = vi.spyOn(window, 'print').mockImplementation(() => {});
    fakeServer(bookOf([asItem(lemon)]));
    renderBook();
    await cover();
    const button = screen.getByRole('button', { name: 'Print' });
    expect(button).toHaveAttribute('type', 'button');
    await userEvent.click(button);
    expect(print).toHaveBeenCalledTimes(1);
    print.mockRestore();
  });

  test('theControlsDoNotPrint', async () => {
    fakeServer(bookOf([asItem(lemon)]));
    renderBook();
    await cover();
    expect(screen.getByRole('button', { name: 'Print' }).closest('.print\\:hidden')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Delete' }).closest('.print\\:hidden')).not.toBeNull();
    expect(screen.getByRole('navigation', { name: 'Pages of this book' })).toHaveClass('print:hidden');
  });

  test('everyLeafAfterTheFirstStartsANewPrintedPage', async () => {
    fakeServer(bookOf([asItem(lemon), reviewStub]));
    renderBook('?page=3');
    await cover();
    const leaves = [...document.querySelectorAll('[data-leaf]')];
    expect(leaves.map((l) => l.classList.contains('print:break-before-page'))).toEqual([false, true, true, true]);
  });

  test('theCoverKeepsItsColourOnPaper', async () => {
    fakeServer(bookOf([asItem(lemon)]));
    renderBook();
    await cover();
    const heading = within(activeLeaf()).getByRole('heading', { level: 1 });
    expect(heading.parentElement).toHaveClass('[print-color-adjust:exact]');
  });

  test('aPrintedPhotoFillsTheWidthAtAFixedHeight', async () => {
    fakeServer(bookOf([asItem(lemon)]));
    renderBook('?page=3');
    await cover();
    const frame = activeLeaf().querySelector('img')!.parentElement!;
    expect(frame).toHaveClass('print:aspect-auto', 'print:h-72');
    expect(frame).not.toHaveClass('print:max-h-72');
  });

  test('theRecipePageLinkDoesNotPrint', async () => {
    fakeServer(bookOf([asItem(lemon)]));
    renderBook('?page=3');
    await cover();
    const link = within(activeLeaf()).getByRole('link', { name: /open the recipe page/i });
    expect(link.closest('.print\\:hidden')).not.toBeNull();
  });
});

test('aMissingBookShowsNotFound', async () => {
  fakeServer(null);
  renderBook();
  expect(await screen.findByText(/cookbook not found/i)).toBeInTheDocument();
});
