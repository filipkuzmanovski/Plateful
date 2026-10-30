import { http, HttpResponse } from 'msw';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '@/test/server';
import { AuthProvider } from '@/auth/AuthProvider';
import AppRoutes from '@/AppRoutes';
import { PreferencesForm } from './PreferencesForm';
import { profileFixture, pageOf } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import type { Preferences } from '@/lib/types';

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

const ALLERGENS = [
  { key: 'gluten', label: 'Gluten' },
  { key: 'eggs', label: 'Eggs' },
  { key: 'milk', label: 'Milk' },
  { key: 'peanuts', label: 'Peanuts' },
];

const newQueryClient = () =>
  new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });

function LocationProbe() {
  return <span data-testid="path">{useLocation().pathname}</span>;
}

/** Stubs the reference data and records PUT bodies and suggest queries. */
function stubServer(suggestions: string[] = []) {
  const puts: unknown[] = [];
  const suggests: string[] = [];
  server.use(
    http.get(`${BASE}/api/food/allergens`, () => HttpResponse.json(ALLERGENS)),
    http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json([])),
    http.get(`${BASE}/api/ingredients/suggest`, ({ request }) => {
      suggests.push(new URL(request.url).searchParams.get('q') ?? '');
      return HttpResponse.json(suggestions);
    }),
    http.put(`${BASE}/api/users/me/preferences`, async ({ request }) => {
      const body = await request.json();
      puts.push(body);
      return HttpResponse.json(body);
    }),
    http.get(`${BASE}/api/recipes`, () => HttpResponse.json(pageOf([]))),
  );
  return { puts, suggests };
}

/** A signed-in user who has not saved preferences yet, on the setup page. */
function renderSetup() {
  mocks.session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  let hasPreferences = false;
  server.use(http.get(`${BASE}/api/users/me`, () => HttpResponse.json({ ...profileFixture, hasPreferences })));
  const view = render(
    <QueryClientProvider client={newQueryClient()}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/preferences/setup']}>
          <LocationProbe />
          <AppRoutes />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
  return { ...view, markSaved: () => { hasPreferences = true; } };
}

const dislikes = () => screen.getByLabelText("Ingredients I don't eat");
const likes = () => screen.getByLabelText('Favourite ingredients');
const allergyGroup = () => screen.getByRole('group', { name: 'Food allergies' });

async function addChip(input: HTMLElement, text: string) {
  await userEvent.type(input, `${text}{Enter}`);
}

async function setupReady() {
  await screen.findByRole('heading', { name: 'Your food preferences' });
  await screen.findByRole('checkbox', { name: 'Milk' });
}

describe('setup page', () => {
  it('shows the title, subtitle and the exact legends', async () => {
    stubServer();
    renderSetup();
    await setupReady();
    expect(
      screen.getByText(
        'We use these to suggest recipes. Allergies and ingredients you avoid are always filtered out.',
      ),
    ).toBeInTheDocument();
    expect(allergyGroup()).toBeInTheDocument();
    const diet = screen.getByRole('group', { name: 'Diet' });
    for (const label of ['Vegetarian', 'Vegan', 'Gluten-free', 'Dairy-free']) {
      expect(within(diet).getByRole('checkbox', { name: label })).toBeInTheDocument();
    }
    expect(screen.getByRole('group', { name: 'What I like' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save preferences' })).toBeInTheDocument();
    expectNoEmoji();
  });

  it('shows the allergy error on the group and sends nothing when nothing is chosen', async () => {
    const { puts } = stubServer();
    renderSetup();
    await setupReady();
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));

    const alert = await screen.findByText('Choose your allergies, or tick I have no food allergies.');
    expect(allergyGroup()).toHaveAttribute('aria-describedby', alert.id);
    expect(screen.getByRole('checkbox', { name: 'Milk' })).not.toHaveAttribute('aria-describedby');
    expect(puts).toHaveLength(0);
    expectNoEmoji();
  });

  it('clears the allergy error once something is chosen', async () => {
    stubServer();
    renderSetup();
    await setupReady();
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    await screen.findByText('Choose your allergies, or tick I have no food allergies.');
    await userEvent.click(screen.getByRole('checkbox', { name: 'Eggs' }));
    expect(screen.queryByText('Choose your allergies, or tick I have no food allergies.')).not.toBeInTheDocument();
    expect(allergyGroup()).not.toHaveAttribute('aria-describedby');
  });

  it('makes "no allergies" and the allergens exclusive of each other', async () => {
    stubServer();
    renderSetup();
    await setupReady();
    const milk = screen.getByRole('checkbox', { name: 'Milk' });
    const none = screen.getByRole('checkbox', { name: 'I have no food allergies' });

    await userEvent.click(milk);
    expect(milk).toBeChecked();
    await userEvent.click(none);
    expect(none).toBeChecked();
    expect(milk).not.toBeChecked();

    await userEvent.click(milk);
    expect(milk).toBeChecked();
    expect(none).not.toBeChecked();
  });

  it('suggests ingredients after the debounce and adds a chip when one is clicked', async () => {
    const { suggests } = stubServer(['garlic', 'garlic powder']);
    renderSetup();
    await setupReady();
    await userEvent.type(dislikes(), 'gar');
    expect(suggests).toHaveLength(0); // the 300 ms debounce has not elapsed

    await userEvent.click(await screen.findByRole('button', { name: 'garlic' }));
    expect(suggests).toEqual(['gar']); // one request for the word, not one per keystroke
    expect(screen.getByRole('button', { name: 'Remove garlic' })).toBeInTheDocument();
    expect(dislikes()).toHaveValue('');
    expect(screen.queryByRole('button', { name: 'garlic powder' })).not.toBeInTheDocument();
  });

  it('shows no suggestion list when the lookup fails', async () => {
    stubServer();
    server.use(http.get(`${BASE}/api/ingredients/suggest`, () => HttpResponse.json({ detail: 'boom' }, { status: 500 })));
    renderSetup();
    await setupReady();
    await userEvent.type(dislikes(), 'gar');
    await new Promise((r) => setTimeout(r, 500));
    expect(screen.queryByRole('list', { name: /suggestions/ })).not.toBeInTheDocument();
  });

  it('adds a chip on Enter and on a comma', async () => {
    stubServer();
    renderSetup();
    await setupReady();
    await addChip(dislikes(), 'okra');
    await userEvent.type(dislikes(), 'kale,');
    expect(screen.getByRole('button', { name: 'Remove okra' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove kale' })).toBeInTheDocument();
    expect(dislikes()).toHaveValue('');
  });

  it('ignores a duplicate regardless of case', async () => {
    stubServer();
    renderSetup();
    await setupReady();
    await addChip(dislikes(), 'okra');
    await addChip(dislikes(), 'Okra');
    expect(screen.getAllByRole('button', { name: /^Remove / })).toHaveLength(1);
  });

  it('removes a chip with its remove button', async () => {
    stubServer();
    renderSetup();
    await setupReady();
    await addChip(dislikes(), 'okra');
    await userEvent.click(screen.getByRole('button', { name: 'Remove okra' }));
    expect(screen.queryByRole('button', { name: 'Remove okra' })).not.toBeInTheDocument();
  });

  it('saves the choices, marks the profile and lands on the home page', async () => {
    const { puts } = stubServer();
    const view = renderSetup();
    await setupReady();
    await userEvent.click(screen.getByRole('checkbox', { name: 'I have no food allergies' }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'Vegan' }));
    await addChip(dislikes(), 'okra');
    await addChip(likes(), 'basil');
    view.markSaved();
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));

    await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent(/^\/$/));
    expect(puts).toEqual([
      {
        allergens: [],
        diets: ['vegan'],
        dislikedIngredients: ['okra'],
        likedIngredients: ['basil'],
        likedCuisines: [],
      },
    ]);
    // Landed on home as a ready user, not bounced back to the setup page.
    expect(await screen.findByRole('button', { name: /Jane/ })).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent(/^\/$/);
  });

  it('shows the server detail when the save fails', async () => {
    stubServer();
    server.use(
      http.put(`${BASE}/api/users/me/preferences`, () =>
        HttpResponse.json({ detail: 'Unknown allergen: kryptonite' }, { status: 400 }),
      ),
    );
    renderSetup();
    await setupReady();
    await userEvent.click(screen.getByRole('checkbox', { name: 'Milk' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Unknown allergen: kryptonite');
    expect(screen.getByTestId('path')).toHaveTextContent('/preferences/setup');
    expectNoEmoji();
  });
});

describe('PreferencesForm', () => {
  function renderForm(initial?: Preferences) {
    const onSave = vi.fn(async () => {});
    render(
      <QueryClientProvider client={newQueryClient()}>
        <PreferencesForm initial={initial} submitLabel="Update" onSave={onSave} />
      </QueryClientProvider>,
    );
    return onSave;
  }

  it('starts with "no allergies" ticked for saved preferences with no allergens', async () => {
    stubServer();
    renderForm({ allergens: [], diets: [], dislikedIngredients: [], likedIngredients: [], likedCuisines: [] });
    expect(await screen.findByRole('checkbox', { name: 'Milk' })).not.toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'I have no food allergies' })).toBeChecked();
  });

  it('starts with the saved allergens and diets ticked', async () => {
    stubServer();
    renderForm({ allergens: ['milk'], diets: ['vegan'], dislikedIngredients: [], likedIngredients: [], likedCuisines: [] });
    expect(await screen.findByRole('checkbox', { name: 'Milk' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'I have no food allergies' })).not.toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Vegan' })).toBeChecked();
  });

  it('disables the input at 30 chips and says why', async () => {
    stubServer();
    const thirty = Array.from({ length: 30 }, (_, i) => `item${String.fromCharCode(97 + (i % 26))}${i}`);
    renderForm({ allergens: ['milk'], diets: [], dislikedIngredients: thirty, likedIngredients: [], likedCuisines: [] });
    await screen.findByRole('checkbox', { name: 'Milk' });
    expect(dislikes()).toBeDisabled();
    expect(screen.getByText('Up to 30 ingredients')).toBeInTheDocument();
    expect(likes()).toBeEnabled();
  });

  it('allows up to 20 favourite cuisines', async () => {
    stubServer();
    const twenty = Array.from({ length: 20 }, (_, i) => `cuisine-${i}`);
    renderForm({ allergens: ['milk'], diets: [], dislikedIngredients: [], likedIngredients: [], likedCuisines: twenty });
    await screen.findByRole('checkbox', { name: 'Milk' });
    expect(screen.getByText('Up to 20 cuisines')).toBeInTheDocument();
  });

  it('uses the submit label and passes the cleaned preferences to onSave', async () => {
    stubServer();
    const onSave = renderForm();
    await userEvent.click(await screen.findByRole('checkbox', { name: 'Peanuts' }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'Dairy-free' }));
    await userEvent.click(screen.getByRole('button', { name: 'Update' }));
    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect(onSave).toHaveBeenCalledWith({
      allergens: ['peanuts'],
      diets: ['dairy_free'],
      dislikedIngredients: [],
      likedIngredients: [],
      likedCuisines: [],
    });
  });
});
