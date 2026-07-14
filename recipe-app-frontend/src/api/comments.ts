import { api, apiVoid } from './client';
import type { CommentDisplay, Page } from '@/lib/types';

export const getComments = (recipeId: string, page: number) =>
  api<Page<CommentDisplay>>(`/api/recipes/${recipeId}/comments?page=${page}&size=10`);
export const addComment = (recipeId: string, body: string) =>
  api<CommentDisplay>(`/api/recipes/${recipeId}/comments`, { method: 'POST', body: JSON.stringify({ body }) });
export const editComment = (id: string, body: string) =>
  api<CommentDisplay>(`/api/comments/${id}`, { method: 'PUT', body: JSON.stringify({ body }) });
export const deleteComment = (id: string) => apiVoid(`/api/comments/${id}`, { method: 'DELETE' });
