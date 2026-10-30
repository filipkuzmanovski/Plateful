import { api } from './client';
import type { Allergen } from '@/lib/types';

export const allergensQueryKey = ['allergens'] as const;

export const getAllergens = () => api<Allergen[]>('/api/food/allergens');
export const suggestIngredients = (q: string) =>
  api<string[]>(`/api/ingredients/suggest?q=${encodeURIComponent(q)}`);
