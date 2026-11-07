import { ApiError, api, apiVoid } from './client';
import type { ReportReason } from './reports';
import type { Page } from '@/lib/types';

export interface ReportEntry {
  reason: ReportReason;
  note: string | null;
  createdAt: string;
}

/** One reported recipe or comment in the admin queue. Never carries who reported it. */
export interface ReportedItem {
  kind: 'recipe' | 'comment';
  id: string;
  recipeId: string;
  /** A comment's text, or a recipe's title. */
  text: string;
  /** Null for an imported recipe. */
  authorName: string | null;
  postedAt: string;
  openCount: number;
  hidden: boolean;
  /** Oldest first. */
  reports: ReportEntry[];
}

/** The reported items waiting for an admin, in the order the server ranks them. */
export const getReports = (page: number): Promise<Page<ReportedItem>> =>
  api(`/api/admin/reports?page=${page}`);

/** The number of reported items waiting for an admin. */
export const getReportCount = async (): Promise<number> =>
  (await api<{ count: number }>('/api/admin/reports/count')).count;

/** Closes the open reports on a recipe without removing it; a hidden recipe shows again. */
export const dismissRecipeReports = (id: string) =>
  apiVoid(`/api/admin/reports/recipes/${encodeURIComponent(id)}/dismiss`, { method: 'POST' });

/** Closes the open reports on a comment without removing it; a hidden comment shows again. */
export const dismissCommentReports = (id: string) =>
  apiVoid(`/api/admin/reports/comments/${encodeURIComponent(id)}/dismiss`, { method: 'POST' });

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
