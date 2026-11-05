import { useCallback, useMemo } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { getLikedIds, likeRecipe, unlikeRecipe } from '@/api/likes';
import { useOptimisticIds } from '@/lib/useOptimisticIds';
import { patchLikeCount } from './likeCounts';

/**
 * The signed-in user's liked recipe ids, plus an optimistic toggle that also moves the public count. The Liked
 * list is deliberately not refetched here: an unliked card stays in place until the list next loads, so focus
 * is not lost and a mistake can be undone.
 */
export function useLikedIds() {
  const queryClient = useQueryClient();
  const onOptimistic = useCallback(
    (id: string, nowLiked: boolean) => patchLikeCount(queryClient, id, nowLiked ? 1 : -1),
    [queryClient],
  );
  const { isOn, isPending, toggle, ready, ids } = useOptimisticIds({
    keyName: 'likedIds',
    fetchIds: getLikedIds,
    add: likeRecipe,
    remove: unlikeRecipe,
    errorMessage: "Couldn't update your like. Please try again.",
    onOptimistic,
  });
  return { isLiked: isOn, isPending, toggle, ready, ids: useMemo(() => ids ?? [], [ids]) };
}
