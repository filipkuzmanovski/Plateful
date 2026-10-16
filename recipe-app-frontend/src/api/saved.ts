import { api, apiVoid } from './client';
import { PAGE_SIZE } from './recipes';
import type { Page, RecipeDisplay } from '@/lib/types';

export const getSavedIds = () => api<string[]>('/api/users/me/saved/ids');

export const getSavedRecipes = (page: number, size: number = PAGE_SIZE): Promise<Page<RecipeDisplay>> =>
  api(`/api/users/me/saved?${new URLSearchParams({ page: String(page), size: String(size) })}`);

export const saveRecipe = (id: string) =>
  apiVoid(`/api/users/me/saved/${encodeURIComponent(id)}`, { method: 'PUT' });

export const unsaveRecipe = (id: string) =>
  apiVoid(`/api/users/me/saved/${encodeURIComponent(id)}`, { method: 'DELETE' });
