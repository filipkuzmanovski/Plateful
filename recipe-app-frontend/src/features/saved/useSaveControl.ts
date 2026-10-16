import { useAuth } from '@/auth/AuthProvider';
import { useSavedIds } from './useSavedIds';

/**
 * What a save button needs for one recipe. Signed in, it is disabled until the saved ids have loaded
 * (a click would be ignored) and while its own request is in flight. Signed out it stays enabled, so
 * a click goes to sign-in; only the brief auth check at startup disables it.
 */
export function useSaveControl(recipeId: string) {
  const { status } = useAuth();
  const { isSaved, isPending, toggle, ready } = useSavedIds();
  const pending = isPending(recipeId);
  const waiting = status === 'loading' || (status === 'ready' && !ready);
  return {
    saved: isSaved(recipeId),
    disabled: pending || waiting,
    toggle: () => toggle(recipeId),
  };
}
