import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { pageOf, profileFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import type { SavedItem } from '@/lib/types';

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

const UNDER_REVIEW_COPY = 'This recipe is being reviewed after an edit. Check back soon.';
const UNAVAILABLE_COPY = "This recipe isn't available right now.";

function stub(id: string, approvedTitle: string, state: 'under_review' | 'unavailable'): SavedItem {
  return { kind: 'stub', recipe: null, stub: { id, approvedTitle, state } };
}

function signedIn() {
  mocks.session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  server.use(http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)));
}

/** A saved list of stubs whose ids, list and unsave share one state. */
function fakeSavedStubs(items: SavedItem[]) {
  const state = { items: [...items], deletes: [] as string[] };
  const idOf = (i: SavedItem) => (i.kind === 'stub' ? i.stub.id : i.recipe.id);
  server.use(
    http.get(`${BASE}/api/users/me/saved/ids`, () => HttpResponse.json(state.items.map(idOf))),
    http.get(`${BASE}/api/users/me/saved`, () => HttpResponse.json(pageOf(state.items))),
    http.delete(`${BASE}/api/users/me/saved/:id`, ({ params }) => {
      state.deletes.push(params.id as string);
      state.items = state.items.filter((i) => idOf(i) !== params.id);
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return state;
}

function hidden(id: string, code: 'under_review' | 'unavailable' | undefined, approvedTitle = 'Grandma Goulash') {
  server.use(
    http.get(`${BASE}/api/recipes/${id}`, () =>
      HttpResponse.json(
        { status: 404, detail: 'Recipe not found', ...(code ? { code, approvedTitle } : {}) },
        { status: 404 },
      ),
    ),
  );
}

beforeEach(() => {
  mocks.session = null;
});

describe('saved stub cards', () => {
  test('stubCardShowsApprovedTitleAndBadge', async () => {
    signedIn();
    fakeSavedStubs([stub('h-1', 'Edited Tart', 'under_review'), stub('h-2', 'Gone Gratin', 'unavailable')]);
    renderApp('/me?tab=saved');

    const reviewCard = (await screen.findByRole('heading', { name: 'Edited Tart' })).closest('a') as HTMLElement;
    expect(within(reviewCard).getByText('Under review')).toBeInTheDocument();
    expect(reviewCard).toHaveAttribute('href', '/recipes/h-1');
    expect(reviewCard.querySelector('img')).toBeNull();

    const goneCard = screen.getByRole('heading', { name: 'Gone Gratin' }).closest('a') as HTMLElement;
    expect(within(goneCard).getByText('Unavailable')).toBeInTheDocument();
    expect(goneCard).toHaveAttribute('href', '/recipes/h-2');
    expect(goneCard.querySelector('img')).toBeNull();

    // The bookmark is a sibling of the link, never inside it.
    const bookmark = await screen.findByRole('button', { name: 'Remove Edited Tart from saved' });
    expect(reviewCard).not.toContainElement(bookmark);
    expectNoEmoji();
  });

  test('stubBookmarkUnsaves', async () => {
    signedIn();
    const state = fakeSavedStubs([stub('h-1', 'Edited Tart', 'under_review')]);
    renderApp('/me?tab=saved');

    const remove = await screen.findByRole('button', { name: 'Remove Edited Tart from saved' });
    await waitFor(() => expect(remove).toBeEnabled());
    await userEvent.click(remove);

    await waitFor(() => expect(state.deletes).toEqual(['h-1']));
    await waitFor(() => expect(screen.queryByText('Edited Tart')).not.toBeInTheDocument());
    expect(
      await screen.findByText('No saved recipes yet. Tap the bookmark on any recipe to save it here.'),
    ).toBeInTheDocument();
    expectNoEmoji();
  });
});

describe('hidden recipe pages', () => {
  test('underReviewPageCopy', async () => {
    signedIn();
    fakeSavedStubs([stub('h-1', 'Grandma Goulash', 'under_review')]);
    hidden('h-1', 'under_review');
    renderApp('/recipes/h-1');

    expect(await screen.findByRole('heading', { name: 'Grandma Goulash' })).toBeInTheDocument();
    expect(screen.getByText(UNDER_REVIEW_COPY)).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Saved' })).toBeInTheDocument();
    expect(screen.queryByText('Recipe not found.')).not.toBeInTheDocument();
    expectNoEmoji();
  });

  test('unavailablePageCopy', async () => {
    signedIn();
    fakeSavedStubs([stub('h-2', 'Grandma Goulash', 'unavailable')]);
    hidden('h-2', 'unavailable');
    renderApp('/recipes/h-2');

    expect(await screen.findByRole('heading', { name: 'Grandma Goulash' })).toBeInTheDocument();
    expect(screen.getByText(UNAVAILABLE_COPY)).toBeInTheDocument();
    expect(screen.queryByText(UNDER_REVIEW_COPY)).not.toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Saved' })).toBeInTheDocument();
    expectNoEmoji();
  });

  test('hiddenPageSaveButtonUnsaves', async () => {
    signedIn();
    const state = fakeSavedStubs([stub('h-1', 'Grandma Goulash', 'under_review')]);
    hidden('h-1', 'under_review');
    renderApp('/recipes/h-1');

    const saved = await screen.findByRole('button', { name: 'Saved' });
    await waitFor(() => expect(saved).toBeEnabled());
    await userEvent.click(saved);
    await waitFor(() => expect(state.deletes).toEqual(['h-1']));
    expect(await screen.findByRole('button', { name: 'Save' })).toBeInTheDocument();
  });

  test('signedOutVisitorSeesUnderReviewPage', async () => {
    hidden('h-1', 'under_review');
    renderApp('/recipes/h-1');

    expect(await screen.findByRole('heading', { name: 'Grandma Goulash' })).toBeInTheDocument();
    expect(screen.getByText(UNDER_REVIEW_COPY)).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Save' })).toBeInTheDocument();
    expectNoEmoji();
  });

  test('plainNotFoundUnchanged', async () => {
    signedIn();
    hidden('missing', undefined);
    renderApp('/recipes/missing');

    expect(await screen.findByText('Recipe not found.')).toBeInTheDocument();
    expect(screen.queryByText(UNDER_REVIEW_COPY)).not.toBeInTheDocument();
    expect(screen.queryByText(UNAVAILABLE_COPY)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Save/ })).not.toBeInTheDocument();
    expectNoEmoji();
  });
});
