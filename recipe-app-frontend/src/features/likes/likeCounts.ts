import type { QueryClient } from '@tanstack/react-query';
import type { RecipeDisplay, SavedItem } from '@/lib/types';

type UnknownRecord = Record<string, unknown>;

const isRecord = (value: unknown): value is UnknownRecord =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const isRecipe = (value: unknown): value is RecipeDisplay =>
  isRecord(value) && typeof value.id === 'string' && typeof value.likeCount === 'number';

const bump = (recipe: RecipeDisplay, delta: number): RecipeDisplay => ({
  ...recipe,
  likeCount: Math.max(0, recipe.likeCount + delta),
});

/** One entry of a list: a recipe, or a saved item carrying one. Anything else comes back as it was. */
function patchEntry(entry: unknown, recipeId: string, delta: number): unknown {
  if (isRecipe(entry)) return entry.id === recipeId ? bump(entry, delta) : entry;
  if (isRecord(entry) && isRecipe(entry.recipe) && entry.recipe.id === recipeId) {
    return { ...entry, recipe: bump(entry.recipe, delta) } as SavedItem;
  }
  return entry;
}

function patchList(list: unknown[], recipeId: string, delta: number): unknown[] {
  const patched = list.map((entry) => patchEntry(entry, recipeId, delta));
  return patched.some((entry, i) => entry !== list[i]) ? patched : list;
}

/**
 * Cached data with one recipe's like count changed: a recipe, a page (of recipes or saved items) or a cookbook's
 * chapters. Unknown shapes, and data that does not hold the recipe, come back as they were.
 */
export function withLikeDelta<T>(data: T, recipeId: string, delta: number): T {
  if (isRecipe(data)) return patchEntry(data, recipeId, delta) as T;
  if (!isRecord(data)) return data;
  for (const field of ['content', 'chapters']) {
    const list = data[field];
    if (Array.isArray(list)) {
      const patched = patchList(list, recipeId, delta);
      return patched === list ? data : ({ ...data, [field]: patched } as T);
    }
  }
  return data;
}

const CACHES = ['recipes', 'userRecipes', 'savedRecipes', 'likedRecipes', 'recipe', 'cookbook'];

/** Changes the count everywhere the recipe is cached, so every card and page agrees without a refetch. */
export function patchLikeCount(queryClient: QueryClient, recipeId: string, delta: number): void {
  for (const name of CACHES) {
    queryClient.setQueriesData({ queryKey: [name] }, (data: unknown) => withLikeDelta(data, recipeId, delta));
  }
}
