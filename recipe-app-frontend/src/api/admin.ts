import { ApiError, api, apiVoid } from './client';
import { PAGE_SIZE } from './recipes';
import type { Page, RecipeDisplay } from '@/lib/types';

/** The review queue, oldest submission first. Every item carries its `review` block. */
export const getPendingRecipes = (page: number, size: number = PAGE_SIZE): Promise<Page<RecipeDisplay>> =>
  api(`/api/admin/recipes/pending?${new URLSearchParams({ page: String(page), size: String(size) })}`);

export const getPendingCount = async (): Promise<number> =>
  (await api<{ count: number }>('/api/admin/recipes/pending/count')).count;

export const getReviewRecipe = (id: string) =>
  api<RecipeDisplay>(`/api/admin/recipes/${encodeURIComponent(id)}`);

/** `revision` is the one the admin read; the API answers 409 `recipe_changed` if the author edited since. */
export const approveRecipe = (id: string, revision: number) =>
  apiVoid(`/api/admin/recipes/${encodeURIComponent(id)}/approve`, {
    method: 'POST',
    body: JSON.stringify({ revision }),
  });

export const rejectRecipe = (id: string, revision: number, reason: string) =>
  apiVoid(`/api/admin/recipes/${encodeURIComponent(id)}/reject`, {
    method: 'POST',
    body: JSON.stringify({ revision, reason }),
  });

/** True for the 403 an admin gets until they have set up two-factor authentication. */
export const isAdminMfaSetupRequired = (e: unknown): boolean =>
  e instanceof ApiError && e.status === 403 && e.code === 'admin_mfa_setup_required';
