import type { CommentDisplay, Page, RecipeDisplay, UserDisplay } from '@/lib/types';

export const profileFixture: UserDisplay = { id: 'u-1', firstName: 'Jane', lastName: 'Doe' };

export function recipeFixture(overrides: Partial<RecipeDisplay> = {}): RecipeDisplay {
  return {
    id: 'r-1', source: 'spoonacular', user: null, title: 'Bruschetta Pork & Pasta',
    image: null, servings: 5, readyInMinutes: 35, cookingMinutes: 25, preparationMinutes: 10,
    sourceName: 'Pink When', sourceUrl: 'https://example.com/r', instructions: 'Cook the pasta. Serve.',
    instructionsSteps: [
      { number: 1, step: 'Boil the pasta.', ingredients: ['pasta'], equipment: ['pot'] },
      { number: 2, step: 'Sear the pork.', ingredients: ['pork chops'], equipment: [] },
    ],
    dairyFree: true, glutenFree: true, vegan: false, vegetarian: false, cuisines: ['italian'],
    calories: 543.36, proteinGrams: 21.1, fatGrams: 16.2, carbsGrams: 74.79, nutrition: null,
    ingredients: [
      { ingredientName: 'penne', originalText: '8 oz penne pasta' },
      { ingredientName: 'pork chops', originalText: '2 boneless pork chops' },
    ],
    averageRating: 4.6, ratingCount: 28, commentCount: 2, createdAt: '2026-07-14T00:00:00Z',
    ...overrides,
  };
}

export function commentFixture(overrides: Partial<CommentDisplay> = {}): CommentDisplay {
  return {
    id: 'c-1', user: profileFixture, body: 'Delicious!', likeCount: 3, dislikeCount: 0,
    createdAt: '2026-07-14T00:00:00Z', ...overrides,
  };
}

export function pageOf<T>(content: T[], pageOverrides = {}): Page<T> {
  return {
    content,
    page: { size: 12, number: 0, totalElements: content.length, totalPages: 1, ...pageOverrides },
  };
}
