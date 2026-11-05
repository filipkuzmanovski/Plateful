import { act, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { RecipeBody } from '@/features/recipe/RecipeBody';
import { AiRecipeView } from '@/features/ai/AiRecipeView';
import type { GeneratedRecipe, NutritionEstimate as Estimate, RecipeDisplay } from '@/lib/types';
import { recipeFixture } from '@/test/fixtures';
import { expectNoEmoji } from '@/test/noEmoji';
import { NutritionEstimate } from './NutritionEstimate';

const ready: Estimate = { status: 'ready', calories: 450, proteinGrams: 28, fatGrams: 18, carbsGrams: 42 };
const pending: Estimate = { status: 'pending', calories: null, proteinGrams: null, fatGrams: null, carbsGrams: null };

function wrap(ui: React.ReactNode) {
  return (
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>
  );
}
const renderEstimate = (estimate: Estimate | null | undefined) =>
  render(wrap(<NutritionEstimate estimate={estimate} queryKey={['recipe', 'r-1']} />));
const renderBody = (recipe: RecipeDisplay) => render(wrap(<RecipeBody recipe={recipe} />));

const aiRecipe: GeneratedRecipe = {
  id: 'a-1', kind: 'cook', mealType: null, title: 'Soup', description: '', servings: 2, prepMinutes: 5, cookMinutes: 10,
  cuisine: null, ingredients: [{ name: 'water', amount: '1 cup', extra: false }], steps: ['Boil.'],
  diets: { vegetarian: true, vegan: true, glutenFree: true, dairyFree: true }, allergens: [], saved: false,
  createdAt: '2026-11-05T00:00:00Z', provider: null, origin: null, swaps: [], estimate: ready,
};

afterEach(() => {
  vi.useRealTimers();
});

test('ready shows the label and all four rounded values', () => {
  renderEstimate(ready);
  for (const text of ['AI estimate, per serving', 'about 450 kcal', '28 g protein', '18 g fat', '42 g carbs'])
    expect(screen.getByText(text)).toBeInTheDocument();
  expectNoEmoji();
});

test('ready rounds fractional values', () => {
  renderEstimate({ ...ready, calories: 449.6, proteinGrams: 27.5 });
  expect(screen.getByText('about 450 kcal')).toBeInTheDocument();
  expect(screen.getByText('28 g protein')).toBeInTheDocument();
});

test('pending shows the waiting line inside a polite live region', () => {
  renderEstimate(pending);
  expect(screen.getByText('Working out calories...').closest('[aria-live="polite"]')).not.toBeNull();
  expectNoEmoji();
});

test('the waiting line goes away after 2 minutes', () => {
  vi.useFakeTimers();
  renderEstimate(pending);
  expect(screen.getByText('Working out calories...')).toBeInTheDocument();
  act(() => vi.advanceTimersByTime(120_000));
  expect(screen.queryByText('Working out calories...')).toBeNull();
});

test.each([{ status: 'none', calories: null, proteinGrams: null, fatGrams: null, carbsGrams: null }, null, undefined])(
  'renders nothing for %o',
  (estimate) => {
    const { container } = renderEstimate(estimate as Estimate | null | undefined);
    expect(container).toBeEmptyDOMElement();
  },
);

test('RecipeBody: imported values take priority and carry no AI label', () => {
  renderBody(recipeFixture({ calories: 543.36, estimate: ready }));
  expect(screen.getByText(/543/)).toBeInTheDocument();
  expect(screen.queryByText('AI estimate, per serving')).toBeNull();
});

test('RecipeBody: an uploaded recipe shows its estimate', () => {
  renderBody(
    recipeFixture({ source: 'user', calories: null, proteinGrams: null, fatGrams: null, carbsGrams: null, estimate: ready }),
  );
  expect(screen.getByText('about 450 kcal')).toBeInTheDocument();
  expect(screen.getByText('AI estimate, per serving')).toBeInTheDocument();
});

test('AiRecipeView shows the estimate', () => {
  render(wrap(<AiRecipeView recipe={aiRecipe} />));
  expect(screen.getByText('about 450 kcal')).toBeInTheDocument();
});
