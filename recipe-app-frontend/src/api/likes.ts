import { api, apiVoid } from './client';
import { PAGE_SIZE } from './recipes';
import type { Page, SavedItem } from '@/lib/types';

export const getLikedIds = () => api<string[]>('/api/users/me/likes/ids');

export const getLikedRecipes = (page: number, size: number = PAGE_SIZE): Promise<Page<SavedItem>> =>
  api(`/api/users/me/likes?${new URLSearchParams({ page: String(page), size: String(size) })}`);

export const likeRecipe = (id: string) =>
  apiVoid(`/api/users/me/likes/${encodeURIComponent(id)}`, { method: 'PUT' });

export const unlikeRecipe = (id: string) =>
  apiVoid(`/api/users/me/likes/${encodeURIComponent(id)}`, { method: 'DELETE' });
