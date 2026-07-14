import { api, apiVoid, ApiError } from './client';

export async function getMyRating(recipeId: string): Promise<number | null> {
  try {
    const result = await api<{ rating: number }>(`/api/recipes/${recipeId}/ratings/me`);
    return result.rating;
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

export const putMyRating = (recipeId: string, rating: number) =>
  apiVoid(`/api/recipes/${recipeId}/ratings/me`, { method: 'PUT', body: JSON.stringify({ rating }) });
export const deleteMyRating = (recipeId: string) =>
  apiVoid(`/api/recipes/${recipeId}/ratings/me`, { method: 'DELETE' });
