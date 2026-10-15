import { api, apiVoid } from './client';
import type { Page, RecipeCreate, RecipeDisplay, RecipeFilter, SortKey } from '@/lib/types';

export const PAGE_SIZE = 12;

export function browseRecipes(
  filter: RecipeFilter,
  page: number,
  size: number = PAGE_SIZE,
  sort: SortKey = 'newest',
  q = '',
): Promise<Page<RecipeDisplay>> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filter)) {
    if (Array.isArray(value)) {
      for (const v of value) if (v !== '') params.append(key, String(v));
    } else if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value));
    }
  }
  params.set('page', String(page));
  params.set('size', String(size));
  params.set('sortBy', sort);
  if (q.trim() !== '') params.set('q', q.trim());
  return api(`/api/recipes?${params}`);
}

export const getRecipe = (id: string) => api<RecipeDisplay>(`/api/recipes/${id}`);
export const createRecipe = (dto: RecipeCreate) =>
  api<RecipeDisplay>('/api/recipes', { method: 'POST', body: JSON.stringify(dto) });
export const updateRecipe = (id: string, dto: RecipeCreate) =>
  api<RecipeDisplay>(`/api/recipes/${id}`, { method: 'PUT', body: JSON.stringify(dto) });
export const deleteRecipe = (id: string) => apiVoid(`/api/recipes/${id}`, { method: 'DELETE' });
