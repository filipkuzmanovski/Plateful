import { useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { getSavedIds, saveRecipe, unsaveRecipe } from '@/api/saved';
import { useOptimisticIds } from '@/lib/useOptimisticIds';

/** The signed-in user's saved recipe ids, plus an optimistic toggle. */
export function useSavedIds() {
  const queryClient = useQueryClient();
  const onSettled = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: ['savedRecipes'] });
  }, [queryClient]);
  const { isOn, isPending, toggle, ready } = useOptimisticIds({
    keyName: 'savedIds',
    fetchIds: getSavedIds,
    add: saveRecipe,
    remove: unsaveRecipe,
    errorMessage: "Couldn't update saved recipes. Please try again.",
    onSettled,
  });
  return { isSaved: isOn, isPending, toggle, ready };
}
