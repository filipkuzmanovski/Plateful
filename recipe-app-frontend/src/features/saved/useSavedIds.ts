import { useCallback, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router';
import { toast } from 'sonner';
import { useAuth } from '@/auth/AuthProvider';
import { getSavedIds, saveRecipe, unsaveRecipe } from '@/api/saved';

/** The signed-in user's saved recipe ids, plus an optimistic toggle. */
export function useSavedIds() {
  const { status, profile } = useAuth();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const userId = profile?.id;
  const key = useMemo(() => ['savedIds', userId] as const, [userId]);
  const signedIn = status === 'ready';

  const { data } = useQuery({ queryKey: key, queryFn: getSavedIds, enabled: signedIn });
  const ids = useMemo(() => new Set(data ?? []), [data]);

  // The ref blocks a second toggle synchronously; the state re-renders consumers for isPending.
  const inFlight = useRef(new Set<string>());
  const [pending, setPending] = useState<ReadonlySet<string>>(new Set());

  const isSaved = useCallback((id: string) => ids.has(id), [ids]);
  const isPending = useCallback((id: string) => pending.has(id), [pending]);

  const toggle = useCallback(
    (id: string) => {
      if (!signedIn) {
        navigate('/login', { state: { from: location.pathname + location.search } });
        return;
      }
      if (inFlight.current.has(id)) return;
      inFlight.current.add(id);
      setPending(new Set(inFlight.current));

      const previous = queryClient.getQueryData<string[]>(key) ?? [];
      const wasSaved = previous.includes(id);
      queryClient.setQueryData<string[]>(key, wasSaved ? previous.filter((x) => x !== id) : [...previous, id]);

      (wasSaved ? unsaveRecipe(id) : saveRecipe(id))
        .catch(() => {
          queryClient.setQueryData<string[]>(key, (cur) => {
            const rest = (cur ?? []).filter((x) => x !== id);
            return wasSaved ? [...rest, id] : rest;
          });
          toast.error("Couldn't update saved recipes. Please try again.");
        })
        .finally(() => {
          inFlight.current.delete(id);
          setPending(new Set(inFlight.current));
          void queryClient.invalidateQueries({ queryKey: ['savedRecipes'] });
        });
    },
    [signedIn, navigate, location.pathname, location.search, queryClient, key],
  );

  return { isSaved, isPending, toggle, ready: signedIn && data !== undefined };
}
