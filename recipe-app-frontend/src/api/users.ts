import { api } from './client';
import type { Page, RecipeDisplay, UserDisplay } from '@/lib/types';
import { PAGE_SIZE } from './recipes';

export interface Names { firstName: string; lastName: string; }

export const getMe = () => api<UserDisplay>('/api/users/me');
export const createMe = (names: Names) =>
  api<UserDisplay>('/api/users/me', { method: 'POST', body: JSON.stringify(names) });
export const updateMe = (names: Names) =>
  api<UserDisplay>('/api/users/me', { method: 'PUT', body: JSON.stringify(names) });
export const getUser = (id: string) => api<UserDisplay>(`/api/users/${id}`);
export const getUserRecipes = (id: string, page: number) =>
  api<Page<RecipeDisplay>>(`/api/users/${id}/recipes?page=${page}&size=${PAGE_SIZE}`);
