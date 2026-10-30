import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { profileFixture, pageOf, recipeFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import type { Preferences } from '@/lib/types';

const BASE = 'http://localhost:8080';

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({
        data: { session: { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } } },
      }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
      signOut: async () => {},
      mfa: { listFactors: async () => ({ data: { totp: [], all: [] }, error: null }) },
    },
  },
}));

const ALLERGENS = [
  { key: 'gluten', label: 'Gluten' },
  { key: 'milk', label: 'Milk' },
  { key: 'peanuts', label: 'Peanuts' },
];

const saved: Preferences = {
  allergens: ['milk'],
  diets: ['vegan'],
  dislikedIngredients: ['okra'],
  likedIngredients: ['basil'],
  likedCuisines: ['italian'],
};

/** Stubs the profile page, reference data and the preferences endpoints; `get` is the stored value, or null for a 404. */
function stub(get: Preferences | null) {
  const puts: unknown[] = [];
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/users/u-1/recipes`, () => HttpResponse.json(pageOf([recipeFixture()]))),
    http.get(`${BASE}/api/food/allergens`, () => HttpResponse.json(ALLERGENS)),
    http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json([
      { key: 'italian', label: 'Italian', region: 'europe', seas: ['mediterranean'], count: 40 },
      { key: 'thai', label: 'Thai', region: 'asia', seas: [], count: 12 },
    ])),
    http.get(`${BASE}/api/ingredients/suggest`, () => HttpResponse.json([])),
    http.get(`${BASE}/api/users/me/preferences`, () =>
      get ? HttpResponse.json(get) : HttpResponse.json({ status: 404, detail: 'No preferences' }, { status: 404 }),
    ),
    http.put(`${BASE}/api/users/me/preferences`, async ({ request }) => {
      const body = await request.json();
      puts.push(body);
      return HttpResponse.json(body);
    }),
  );
  return puts;
}

async function ready() {
  await screen.findByRole('checkbox', { name: 'Milk' });
}

describe('preferences panel', () => {
  test('loadsSavedValues', async () => {
    stub(saved);
    renderApp('/me?tab=preferences');
    await ready();

    expect(screen.getByRole('tab', { name: 'Preferences' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel')).toHaveAccessibleName('Preferences');
    expect(screen.getByRole('checkbox', { name: 'Milk' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Gluten' })).not.toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'I have no food allergies' })).not.toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Vegan' })).toBeChecked();
    expect(screen.getByRole('button', { name: 'Remove okra' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove basil' })).toBeInTheDocument();
    expect(screen.getByText('Italian')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save preferences' })).toBeInTheDocument();
    expectNoEmoji();
  });

  test('emptyAllergensTicksNoAllergies', async () => {
    stub({ ...saved, allergens: [] });
    renderApp('/me?tab=preferences');
    await ready();

    expect(screen.getByRole('checkbox', { name: 'I have no food allergies' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Milk' })).not.toBeChecked();
  });

  test('savingSendsPutAndToasts', async () => {
    const puts = stub(saved);
    renderApp('/me?tab=preferences');
    await ready();

    await userEvent.click(screen.getByRole('checkbox', { name: 'Peanuts' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));

    expect(await screen.findByText('Preferences saved')).toBeInTheDocument();
    expect(puts).toEqual([{ ...saved, allergens: ['milk', 'peanuts'] }]);
  });

  test('savedValuesSurviveLeavingAndReturningToTheTab', async () => {
    stub(saved);
    renderApp('/me?tab=preferences');
    await ready();

    await userEvent.click(screen.getByRole('checkbox', { name: 'Peanuts' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    await screen.findByText('Preferences saved');

    // The GET stub still returns the old value; the form must come from the cache the save wrote.
    await userEvent.click(screen.getByRole('tab', { name: 'My recipes' }));
    await userEvent.click(await screen.findByRole('tab', { name: 'Preferences' }));
    await ready();
    expect(screen.getByRole('checkbox', { name: 'Peanuts' })).toBeChecked();
  });

  test('afterSavingTheFormShowsWhatTheServerStored', async () => {
    const puts = stub(saved);
    server.use(
      http.put(`${BASE}/api/users/me/preferences`, async ({ request }) => {
        const body = (await request.json()) as Preferences;
        puts.push(body);
        // The server drops names that normalise to nothing, such as "123".
        return HttpResponse.json({ ...body, dislikedIngredients: body.dislikedIngredients.filter((n) => n !== '123') });
      }),
    );
    renderApp('/me?tab=preferences');
    await ready();

    await userEvent.type(screen.getByLabelText("Ingredients I don't eat"), '123{Enter}');
    expect(screen.getByRole('button', { name: 'Remove 123' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));

    expect(await screen.findByText('Preferences saved')).toBeInTheDocument();
    expect(puts).toEqual([{ ...saved, dislikedIngredients: ['okra', '123'] }]);
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Remove 123' })).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Remove okra' })).toBeInTheDocument();
  });

  test('a404ShowsTheEmptyForm', async () => {
    const puts = stub(null);
    renderApp('/me?tab=preferences');
    await ready();

    for (const name of ['Milk', 'Gluten', 'Peanuts', 'I have no food allergies', 'Vegan'])
      expect(screen.getByRole('checkbox', { name })).not.toBeChecked();
    expect(screen.queryByRole('button', { name: /^Remove / })).not.toBeInTheDocument();
    expect(screen.queryByText("Couldn't load your preferences.")).not.toBeInTheDocument();

    // The allergy answer is still required for a first save.
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    expect(await screen.findByText('Choose your allergies, or tick I have no food allergies.')).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  test('aFailedLoadOffersRetry', async () => {
    stub(saved);
    server.use(
      http.get(`${BASE}/api/users/me/preferences`, () => HttpResponse.json({ detail: 'boom' }, { status: 500 }), {
        once: true,
      }),
    );
    renderApp('/me?tab=preferences');

    expect(await screen.findByText("Couldn't load your preferences.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await ready();
    await waitFor(() => expect(screen.getByRole('checkbox', { name: 'Milk' })).toBeChecked());
  });
});
