import { ApiError, api, apiVoid } from './client';

export const getPendingCount = async (): Promise<number> =>
  (await api<{ count: number }>('/api/admin/recipes/pending/count')).count;

/** An admin takes down any recipe; its author is emailed the reason. */
export const removeRecipe = (id: string, reason: string) =>
  apiVoid(`/api/admin/recipes/${encodeURIComponent(id)}/remove`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });

/** An admin takes down any comment; its author is emailed the reason. */
export const removeComment = (id: string, reason: string) =>
  apiVoid(`/api/admin/comments/${encodeURIComponent(id)}/remove`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });

/** Shown instead of the server's 403 text when an admin tries a moderation action before setting up 2FA. */
export const MFA_SETUP_MESSAGE = 'Set up two-factor authentication to remove content.';

/** True for the 403 an admin gets until they have set up two-factor authentication. */
export const isAdminMfaSetupRequired = (e: unknown): boolean =>
  e instanceof ApiError && e.status === 403 && e.code === 'admin_mfa_setup_required';
