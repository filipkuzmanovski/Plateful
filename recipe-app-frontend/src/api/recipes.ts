import { api, apiVoid } from './client';
import type { Page, RecipeCreate, RecipeDisplay, RecipeFilter } from '@/lib/types';

export const PAGE_SIZE = 12;

export function browseRecipes(filter: RecipeFilter, page: number): Promise<Page<RecipeDisplay>> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filter)) {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value));
  }
  params.set('page', String(page));
  params.set('size', String(PAGE_SIZE));
  return api(`/api/recipes?${params}`);
}

export const getRecipe = (id: string) => api<RecipeDisplay>(`/api/recipes/${id}`);
export const createRecipe = (dto: RecipeCreate) =>
  api<RecipeDisplay>('/api/recipes', { method: 'POST', body: JSON.stringify(dto) });
export const updateRecipe = (id: string, dto: RecipeCreate) =>
  api<RecipeDisplay>(`/api/recipes/${id}`, { method: 'PUT', body: JSON.stringify(dto) });
export const deleteRecipe = (id: string) => apiVoid(`/api/recipes/${id}`, { method: 'DELETE' });
