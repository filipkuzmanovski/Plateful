import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { NutritionEstimate } from '@/lib/types';

const INTERVAL_MS = 10_000;
const MAX_TRIES = 12;

/**
 * While an estimate is pending, re-fetches the page's recipe query every 10 seconds, 12 times at most.
 * Returns whether the page is still waiting; after the last try it returns false and a later visit shows the result.
 */
export function useRefreshWhilePending(
  status: NutritionEstimate['status'] | undefined,
  queryKey: readonly unknown[],
): boolean {
  const queryClient = useQueryClient();
  const keyString = JSON.stringify(queryKey);
  const [gaveUpOn, setGaveUpOn] = useState<string | null>(null);
  const pending = status === 'pending';

  useEffect(() => {
    if (!pending) return;
    let tries = 0;
    const id = setInterval(() => {
      tries += 1;
      void queryClient.invalidateQueries({ queryKey: JSON.parse(keyString) as unknown[] });
      if (tries >= MAX_TRIES) {
        clearInterval(id);
        setGaveUpOn(keyString);
      }
    }, INTERVAL_MS);
    return () => clearInterval(id);
  }, [pending, keyString, queryClient]);

  return pending && gaveUpOn !== keyString;
}
