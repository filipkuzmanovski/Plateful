import { api, apiVoid } from './client';

/** The signed-in user's reactions on a recipe's comments: commentId -> isLike (absent = none). */
export const getMyReactions = (recipeId: string) =>
  api<Record<string, boolean>>(`/api/recipes/${recipeId}/comments/reactions/me`);

export const putMyReaction = (commentId: string, isLike: boolean) =>
  apiVoid(`/api/comments/${commentId}/reactions/me`, { method: 'PUT', body: JSON.stringify({ isLike }) });
export const deleteMyReaction = (commentId: string) =>
  apiVoid(`/api/comments/${commentId}/reactions/me`, { method: 'DELETE' });
