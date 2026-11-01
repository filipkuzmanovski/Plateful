import { http, HttpResponse, delay } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/server';
import { renderApp } from '@/test/utils';
import { profileFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import type { AiUsage, GeneratedRecipe } from '@/lib/types';

const BASE = 'http://localhost:8080';
const GENERATE = `${BASE}/api/users/me/ai-recipes/generate`;

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

function recipeFor(overrides: Partial<GeneratedRecipe> = {}): GeneratedRecipe {
  return {
    id: 'a-1', kind: 'cook', mealType: 'dinner', title: 'Tomato Egg Skillet',
    description: 'Eggs baked in a spiced tomato sauce.', servings: 2, prepMinutes: 10, cookMinutes: 20,
    cuisine: '',
    ingredients: [{ name: 'eggs', amount: '4', extra: false }, { name: 'tomatoes', amount: '3', extra: false }],
    steps: ['Simmer the tomatoes.', 'Crack in the eggs and cover.'],
    diets: { vegetarian: true, vegan: false, glutenFree: true, dairyFree: false },
    allergens: [], saved: false, createdAt: '2026-10-31T10:00:00Z', provider: 'groq',
    ...overrides,
  };
}

const USAGE: AiUsage = { used: 3, limit: 10, remaining: 7, scansUsed: 2, scanLimit: 10, scansRemaining: 8 };

function signedIn(usage: AiUsage = USAGE) {
  mocks.session = { access_token: 't', user: { id: 'u-1', email: 'jane@example.com' } };
  server.use(
    http.get(`${BASE}/api/users/me`, () => HttpResponse.json(profileFixture)),
    http.get(`${BASE}/api/users/me/ai-recipes/usage`, () => HttpResponse.json(usage)),
    http.get(`${BASE}/api/recipes/cuisines`, () => HttpResponse.json([])),
    http.get(`${BASE}/api/ingredients/suggest`, () => HttpResponse.json([])),
  );
}

async function addChips(user: ReturnType<typeof userEvent.setup>, ...names: string[]) {
  const input = await screen.findByLabelText('Ingredients');
  for (const n of names) await user.type(input, `${n}{Enter}`);
}

describe('Cook page', () => {
  it('keeps Generate disabled until there are two ingredients, then sends the payload', async () => {
    signedIn();
    let body: unknown = null;
    server.use(http.post(GENERATE, async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ recipe: recipeFor(), remainingToday: 6 });
    }));
    const user = userEvent.setup();
    renderApp('/cook');

    expect(await screen.findByRole('heading', { name: 'Cook with what I have' })).toBeInTheDocument();
    const generate = screen.getByRole('button', { name: 'Generate' });
    expect(generate).toBeDisabled();
    await addChips(user, 'eggs');
    expect(generate).toBeDisabled();
    await addChips(user, 'tomatoes');
    expect(generate).toBeEnabled();

    await user.click(screen.getByRole('radio', { name: 'Dinner' }));
    await user.click(screen.getByLabelText('Only use what I have'));
    await user.click(generate);

    expect(await screen.findByRole('heading', { name: 'Tomato Egg Skillet' })).toBeInTheDocument();
    expect(body).toEqual({ ingredients: ['eggs', 'tomatoes'], mealType: 'dinner', onlyWhatIHave: true });
    expectNoEmoji();
  });

  it('sends mealType null for Any and offers the four meal choices', async () => {
    signedIn();
    let body: unknown = null;
    server.use(http.post(GENERATE, async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ recipe: recipeFor(), remainingToday: 6 });
    }));
    const user = userEvent.setup();
    renderApp('/cook');

    await addChips(user, 'eggs', 'rice');
    expect(screen.getByRole('group', { name: 'Meal' })).toBeInTheDocument();
    expect(screen.getAllByRole('radio').map((r) => (r as HTMLInputElement).labels?.[0].textContent))
      .toEqual(['Any', 'Breakfast', 'Lunch', 'Dinner']);
    expect(screen.getByRole('radio', { name: 'Any' })).toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Generate' }));
    await screen.findByRole('heading', { name: 'Tomato Egg Skillet' });
    expect(body).toEqual({ ingredients: ['eggs', 'rice'], mealType: null, onlyWhatIHave: false });
  });

  it('shows "7 left today" and updates it from remainingToday', async () => {
    signedIn();
    server.use(http.post(GENERATE, () => HttpResponse.json({ recipe: recipeFor(), remainingToday: 6 })));
    const user = userEvent.setup();
    renderApp('/cook');

    expect(await screen.findByText('7 left today')).toBeInTheDocument();
    await addChips(user, 'eggs', 'tomatoes');
    await user.click(screen.getByRole('button', { name: 'Generate' }));
    expect(await screen.findByText('6 left today')).toBeInTheDocument();
  });

  it('disables Generate and shows the waiting text while the recipe is being made', async () => {
    signedIn();
    server.use(http.post(GENERATE, async () => {
      await delay(150);
      return HttpResponse.json({ recipe: recipeFor(), remainingToday: 6 });
    }));
    const user = userEvent.setup();
    renderApp('/cook');

    await addChips(user, 'eggs', 'tomatoes');
    const generate = screen.getByRole('button', { name: 'Generate' });
    await user.click(generate);
    expect(await screen.findByText('Creating your recipe. This can take up to a minute.')).toBeInTheDocument();
    expect(generate).toBeDisabled();
    expectNoEmoji();

    await screen.findByRole('heading', { name: 'Tomato Egg Skillet' });
    expect(screen.queryByText('Creating your recipe. This can take up to a minute.')).not.toBeInTheDocument();
    expect(generate).toBeEnabled();
  });

  it('Generate another sends the same payload again, even after the chips are edited', async () => {
    signedIn();
    const bodies: unknown[] = [];
    let n = 0;
    server.use(http.post(GENERATE, async ({ request }) => {
      bodies.push(await request.json());
      n += 1;
      return HttpResponse.json({ recipe: recipeFor({ id: `a-${n}`, title: `Skillet ${n}` }), remainingToday: 10 - n });
    }));
    const user = userEvent.setup();
    renderApp('/cook');

    await addChips(user, 'eggs', 'tomatoes');
    await user.click(screen.getByRole('radio', { name: 'Lunch' }));
    await user.click(screen.getByRole('button', { name: 'Generate' }));
    await screen.findByRole('heading', { name: 'Skillet 1' });

    await addChips(user, 'rice');
    await user.click(screen.getByRole('button', { name: 'Generate another' }));
    await screen.findByRole('heading', { name: 'Skillet 2' });
    expect(bodies).toEqual([
      { ingredients: ['eggs', 'tomatoes'], mealType: 'lunch', onlyWhatIHave: false },
      { ingredients: ['eggs', 'tomatoes'], mealType: 'lunch', onlyWhatIHave: false },
    ]);
  });

  it('links Open to the recipe page and Save becomes Saved', async () => {
    signedIn();
    let saved = false;
    server.use(
      http.post(GENERATE, () => HttpResponse.json({ recipe: recipeFor(), remainingToday: 6 })),
      http.get(`${BASE}/api/users/me/ai-recipes/a-1`, () => HttpResponse.json(recipeFor({ saved }))),
      http.put(`${BASE}/api/users/me/ai-recipes/a-1/saved`, () => {
        saved = true; return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderApp('/cook');

    await addChips(user, 'eggs', 'tomatoes');
    await user.click(screen.getByRole('button', { name: 'Generate' }));
    await screen.findByRole('heading', { name: 'Tomato Egg Skillet' });
    expect(screen.getByRole('link', { name: 'Open' })).toHaveAttribute('href', '/ai-recipes/a-1');

    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('button', { name: 'Saved' })).toBeInTheDocument();
    expectNoEmoji();
  });

  it.each([
    ['no_preferences', 409, 'Set your food preferences first.'],
    ['daily_limit', 429, "You've used today's 10 AI recipes. More tomorrow."],
    ['ai_quota', 503, 'The free AI limit is reached for now. Try again later.'],
    ['ai_unavailable', 503, "The AI service isn't responding. Try again in a minute."],
    ['ai_not_configured', 503, "AI features aren't set up on this server."],
    ['generation_failed', 422, "Couldn't make a safe new recipe from these. Try changing the ingredients."],
  ])('shows the message for %s in an alert', async (code, status, message) => {
    signedIn();
    server.use(http.post(GENERATE, () => HttpResponse.json({ detail: 'server detail', code }, { status })));
    const user = userEvent.setup();
    renderApp('/cook');

    await addChips(user, 'eggs', 'tomatoes');
    await user.click(screen.getByRole('button', { name: 'Generate' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    expectNoEmoji();
  });

  it('disables Generate after a daily_limit error, even when ingredients change', async () => {
    signedIn();
    server.use(http.post(GENERATE, () =>
      HttpResponse.json({ detail: 'x', code: 'daily_limit' }, { status: 429 })));
    const user = userEvent.setup();
    renderApp('/cook');

    await addChips(user, 'eggs', 'tomatoes');
    await user.click(screen.getByRole('button', { name: 'Generate' }));
    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: 'Generate' })).toBeDisabled();
    await addChips(user, 'rice');
    expect(screen.getByRole('button', { name: 'Generate' })).toBeDisabled();
    expect(screen.getByText('0 left today')).toBeInTheDocument();
  });

  it('flags the chips named in a 400 with their reasons, and clears the flags when the chips change', async () => {
    signedIn();
    server.use(http.post(GENERATE, () =>
      HttpResponse.json({
        detail: 'Some ingredients clash with your food preferences.', code: 'invalid_request',
        conflicts: [{ ingredient: 'peanut butter', reason: 'allergy: Peanuts' }],
      }, { status: 400 })));
    const user = userEvent.setup();
    renderApp('/cook');

    await addChips(user, 'peanut butter', 'bread');
    await user.click(screen.getByRole('button', { name: 'Generate' }));
    expect(await screen.findByText('peanut butter: allergy: Peanuts')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Some ingredients clash with your food preferences.');
    expect(screen.getAllByText(/allergy:/)).toHaveLength(1); // bread is not flagged

    await user.click(screen.getByRole('button', { name: 'Remove peanut butter' }));
    await waitFor(() => expect(screen.queryByText(/allergy: Peanuts/)).not.toBeInTheDocument());
    expectNoEmoji();
  });
});

describe('Header Cook link', () => {
  it('shows Cook when signed in', async () => {
    signedIn();
    renderApp('/');
    expect(await screen.findByRole('link', { name: 'Cook' })).toHaveAttribute('href', '/cook');
  });

  it('does not show Cook when anonymous', async () => {
    mocks.session = null;
    server.use(http.get(`${BASE}/api/recipes`, () =>
      HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 12 })));
    renderApp('/');
    await screen.findByRole('link', { name: 'Sign in' });
    expect(screen.queryByRole('link', { name: 'Cook' })).not.toBeInTheDocument();
  });
});
