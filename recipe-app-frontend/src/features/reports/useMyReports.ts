import { useEffect, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getMyReports } from '@/api/reports';
import { useAuth } from '@/auth/AuthProvider';

export const myReportsKey = (userId: string | undefined, recipeId: string) => ['myReports', userId, recipeId];

/**
 * What the signed-in user already reported on one recipe page. `commentIds` is the visible comment page
 * (null until it is known); a new page refetches under the same key. Skipped when signed out.
 */
export function useMyReports(recipeId: string, commentIds: string[] | null) {
  const { status, profile } = useAuth();
  const queryClient = useQueryClient();
  const key = myReportsKey(profile?.id, recipeId);
  const idsKey = commentIds?.join(',') ?? null;
  const ids = useRef<string[]>([]);
  ids.current = commentIds ?? [];

  const query = useQuery({
    queryKey: key,
    queryFn: () => getMyReports(recipeId, ids.current),
    enabled: status === 'ready' && commentIds !== null,
  });

  const lastFetched = useRef<string | null>(null);
  useEffect(() => {
    if (idsKey === null) return;
    if (lastFetched.current !== null && lastFetched.current !== idsKey) {
      void queryClient.invalidateQueries({ queryKey: key });
    }
    lastFetched.current = idsKey;
  }, [idsKey, queryClient, key]);

  return query;
}
