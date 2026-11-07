import { api, apiVoid } from './client';

export type ReportReason = 'spam' | 'offensive' | 'unsafe' | 'not_food' | 'other';
export type ReportTarget = { recipeId: string } | { commentId: string };
export interface MyReports { recipeIds: string[]; commentIds: string[] }

export const reportContent = (target: ReportTarget, reason: ReportReason, note: string | null) =>
  apiVoid('/api/reports', { method: 'POST', body: JSON.stringify({ ...target, reason, note }) });

/** Which of this recipe and these comments the signed-in user already reported. */
export const getMyReports = (recipeId: string, commentIds: string[]): Promise<MyReports> => {
  const params = new URLSearchParams({ recipeId });
  for (const id of commentIds) params.append('commentIds', id);
  return api(`/api/reports/mine?${params}`);
};
