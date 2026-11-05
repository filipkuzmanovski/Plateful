import { api, apiVoid } from './client';
import type { Cookbook, CookbookSummary } from '@/lib/types';

export const getCookbooks = () => api<CookbookSummary[]>('/api/users/me/cookbooks');

export const getCookbook = (id: string) => api<Cookbook>(`/api/users/me/cookbooks/${encodeURIComponent(id)}`);

export const createCookbook = (body: { recipeIds: string[]; subtitle?: string }) =>
  api<CookbookSummary>('/api/users/me/cookbooks', { method: 'POST', body: JSON.stringify(body) });

export const deleteCookbook = (id: string) =>
  apiVoid(`/api/users/me/cookbooks/${encodeURIComponent(id)}`, { method: 'DELETE' });
