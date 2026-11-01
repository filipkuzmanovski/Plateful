import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { profileFixture, pageOf } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import type { GeneratedRecipeSummary } from '@/lib/types';

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

function summary(overrides: Partial<GeneratedRecipeSummary> = {}): GeneratedRecipeSummary {
  return {
    id: 'a-1', kind: 'cook', mealType: null, title: 'Tomato Egg Skillet', description: 'Eggs in sauce.',
    totalMinutes: 30, savedAt: '2026-10-28T12:00:00Z', ...overrides,
  };
}

/** A tiny fake server: the saved list and DELETE share one state. */
function fakeAiServer(all: GeneratedRecipeSummary[]) {
  const state = { items: [...all], deletes: [] as string[] };
  server.use(
    http.get(`${BASE}/api/users/me/ai-recipes`, ({ request }) => {
      const url = new URL(request.url);
      const page = Number(url.searchParams.get('page') ?? '0');
      const size = Number(url.searchParams.get('size') ?? '12');
      return HttpResponse.json(
        pageOf(state.items.slice(page * size, (page + 1) * size), {
          number: page, size, totalElements: state.items.length, totalPages: Math.ceil(state.items.length / size),
        }),
      );
    }),
    http.delete(`${BASE}/api/users/me/ai-recipes/:id/saved`, ({ params }) => {
      state.deletes.push(params.id as string);
      state.items = state.items.filter((r) => r.id !== params.id);
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return state;
}

beforeEach(() => {
  mocks.session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/users/u-1/recipes`, () => HttpResponse.json(pageOf([]))),
  );
});

describe('saved AI recipes tab', () => {
  it('lists the title as a link, the kind and the saved date', async () => {
    fakeAiServer([
      summary(),
      summary({ id: 'a-2', kind: 'daily', mealType: 'breakfast', title: 'Oat Pancakes', savedAt: '2026-10-29T12:00:00Z' }),
      summary({ id: 'a-3', kind: 'daily', mealType: 'lunch', title: 'Lentil Soup' }),
      summary({ id: 'a-4', kind: 'daily', mealType: 'dinner', title: 'Baked Cod' }),
    ]);
    renderApp('/me?tab=ai');

    const link = await screen.findByRole('link', { name: 'Tomato Egg Skillet' });
    expect(link).toHaveAttribute('href', '/ai-recipes/a-1');
    const rows = within(screen.getByRole('tabpanel')).getAllByRole('listitem');
    expect(rows).toHaveLength(4);
    expect(rows[0]).toHaveTextContent('Cook');
    expect(rows[0]).toHaveTextContent('Saved 28 October');
    expect(rows[1]).toHaveTextContent('Breakfast');
    expect(rows[1]).toHaveTextContent('Saved 29 October');
    expect(rows[2]).toHaveTextContent('Lunch');
    expect(rows[3]).toHaveTextContent('Dinner');
    expect(within(rows[0]).getByRole('button', { name: 'Remove Tomato Egg Skillet from AI recipes' })).toBeInTheDocument();
    expectNoEmoji();
  });

  it('postRecipe_isLabelledFromAPost', async () => {
    fakeAiServer([summary({ id: 'a-5', kind: 'post', title: 'Charred Corn and Feta Salad' })]);
    renderApp('/me?tab=ai');

    await screen.findByRole('link', { name: 'Charred Corn and Feta Salad' });
    const row = within(screen.getByRole('tabpanel')).getAllByRole('listitem')[0];
    expect(row).toHaveTextContent('From a post');
    expect(row).not.toHaveTextContent('Cook');
    expectNoEmoji();
  });

  it('shows the empty message', async () => {
    fakeAiServer([]);
    renderApp('/me?tab=ai');
    expect(await screen.findByText('No saved AI recipes yet.')).toBeInTheDocument();
    expectNoEmoji();
  });

  it('shows an error message when the list cannot load', async () => {
    server.use(
      http.get(`${BASE}/api/users/me/ai-recipes`, () => HttpResponse.json({ status: 500, detail: 'boom' }, { status: 500 })),
    );
    renderApp('/me?tab=ai');
    expect(await screen.findByText("Couldn't load saved AI recipes.")).toBeInTheDocument();
  });

  it('Remove sends DELETE and the row disappears', async () => {
    const state = fakeAiServer([summary(), summary({ id: 'a-2', title: 'Second Dish' })]);
    renderApp('/me?tab=ai');

    await userEvent.click(await screen.findByRole('button', { name: 'Remove Tomato Egg Skillet from AI recipes' }));

    await waitFor(() => expect(state.deletes).toEqual(['a-1']));
    await waitFor(() => expect(screen.queryByText('Tomato Egg Skillet')).not.toBeInTheDocument());
    expect(screen.getByText('Second Dish')).toBeInTheDocument();
  });

  it('removing the last recipe shows the empty message', async () => {
    fakeAiServer([summary()]);
    renderApp('/me?tab=ai');
    await userEvent.click(await screen.findByRole('button', { name: 'Remove Tomato Egg Skillet from AI recipes' }));
    expect(await screen.findByText('No saved AI recipes yet.')).toBeInTheDocument();
  });

  it('paginates, and removing the last row of the last page steps back', async () => {
    const many = Array.from({ length: 13 }, (_, i) => summary({ id: `a-${i + 1}`, title: `AI Dish ${i + 1}` }));
    const state = fakeAiServer(many);
    renderApp('/me?tab=ai');

    expect(await screen.findByText('AI Dish 1')).toBeInTheDocument();
    expect(screen.queryByText('AI Dish 13')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Go to page 2' }));
    const remove = await screen.findByRole('button', { name: 'Remove AI Dish 13 from AI recipes' });
    expect(screen.queryByText('AI Dish 1')).not.toBeInTheDocument();
    await userEvent.click(remove);

    await waitFor(() => expect(state.deletes).toEqual(['a-13']));
    expect(await screen.findByText('AI Dish 1')).toBeInTheDocument();
    expect(screen.queryByText(/No saved AI recipes yet/)).not.toBeInTheDocument();
  });
});
