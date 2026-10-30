import { api } from './client';
import type { Preferences } from '@/lib/types';

export const preferencesQueryKey = (userId: string) => ['preferences', userId] as const;

/** 404 until the user has saved preferences once. */
export const getPreferences = () => api<Preferences>('/api/users/me/preferences');
export const savePreferences = (p: Preferences) =>
  api<Preferences>('/api/users/me/preferences', { method: 'PUT', body: JSON.stringify(p) });
