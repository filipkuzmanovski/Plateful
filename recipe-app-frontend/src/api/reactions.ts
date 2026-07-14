import { apiVoid } from './client';

export const putMyReaction = (commentId: string, isLike: boolean) =>
  apiVoid(`/api/comments/${commentId}/reactions/me`, { method: 'PUT', body: JSON.stringify({ isLike }) });
export const deleteMyReaction = (commentId: string) =>
  apiVoid(`/api/comments/${commentId}/reactions/me`, { method: 'DELETE' });
