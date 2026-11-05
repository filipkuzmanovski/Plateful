import { useCallback, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router';
import { toast } from 'sonner';
import { useAuth } from '@/auth/AuthProvider';

/**
 * A signed-in user's set of recipe ids (saved, liked) with an optimistic toggle. A toggle on an id already in
 * flight is ignored; a signed-out toggle goes to sign-in. The callbacks must be stable.
 */
export function useOptimisticIds({
  keyName,
  fetchIds,
  add,
  remove,
  errorMessage,
  onOptimistic,
  onSettled,
}: {
  /** The query key is [keyName, userId]. */
  keyName: string;
  fetchIds: () => Promise<string[]>;
  add: (id: string) => Promise<void>;
  remove: (id: string) => Promise<void>;
  errorMessage: string;
  /** Runs when the id's state changes on screen: at the toggle, and with the opposite value when it fails. */
  onOptimistic?: (id: string, nowOn: boolean) => void;
  onSettled?: () => void;
}) {
  const { status, profile } = useAuth();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const userId = profile?.id;
  const key = useMemo(() => [keyName, userId] as const, [keyName, userId]);
  const signedIn = status === 'ready';

  const { data } = useQuery({ queryKey: key, queryFn: fetchIds, enabled: signedIn });
  const ids = useMemo(() => new Set(data ?? []), [data]);

  // The ref blocks a second toggle synchronously; the state re-renders consumers for isPending.
  const inFlight = useRef(new Set<string>());
  const [pending, setPending] = useState<ReadonlySet<string>>(new Set());

  const isOn = useCallback((id: string) => ids.has(id), [ids]);
  const isPending = useCallback((id: string) => pending.has(id), [pending]);

  const toggle = useCallback(
    (id: string) => {
      if (!signedIn) {
        const from = location.pathname + location.search;
        // Half signed-in users go where the rest of the app sends them, not back to the password form.
        if (status === 'needs-mfa') navigate('/verify-2fa', { state: { from } });
        else if (status === 'needs-profile') navigate('/complete-profile');
        else if (status === 'needs-preferences') navigate('/preferences/setup');
        else navigate('/login', { state: { from } });
        return;
      }
      // Until the ids arrive an id already on would look off, so do nothing.
      if (queryClient.getQueryData<string[]>(key) === undefined) return;
      if (inFlight.current.has(id)) return;
      // Claim the id synchronously, before any await, so a second toggle is blocked.
      inFlight.current.add(id);
      setPending(new Set(inFlight.current));

      void (async () => {
        let wasOn = false;
        let shown = false;
        try {
          // A refetch already in flight would otherwise overwrite the optimistic value with stale data.
          await queryClient.cancelQueries({ queryKey: key });
          const previous = queryClient.getQueryData<string[]>(key) ?? [];
          wasOn = previous.includes(id);
          queryClient.setQueryData<string[]>(key, wasOn ? previous.filter((x) => x !== id) : [...previous, id]);
          shown = true;
          onOptimistic?.(id, !wasOn);
          await (wasOn ? remove(id) : add(id));
        } catch {
          queryClient.setQueryData<string[]>(key, (cur) => {
            const rest = (cur ?? []).filter((x) => x !== id);
            return wasOn ? [...rest, id] : rest;
          });
          if (shown) onOptimistic?.(id, wasOn);
          toast.error(errorMessage);
        } finally {
          inFlight.current.delete(id);
          setPending(new Set(inFlight.current));
          onSettled?.();
          void queryClient.invalidateQueries({ queryKey: key });
        }
      })();
    },
    [signedIn, status, navigate, location.pathname, location.search, queryClient, key, add, remove, errorMessage, onOptimistic, onSettled],
  );

  return { isOn, isPending, toggle, ready: signedIn && data !== undefined, ids: data };
}
