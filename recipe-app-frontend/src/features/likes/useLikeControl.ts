import { useAuth } from '@/auth/AuthProvider';
import { useLikedIds } from './useLikedIds';

/**
 * What a like button needs for one recipe. Signed in, it is disabled until the liked ids have loaded (a click
 * would be ignored). Signed out it stays enabled, so a click goes to sign-in; only the brief auth check at
 * startup disables it. While its own request is in flight it is `pending`: the button must stay focusable (a
 * `disabled` button drops keyboard focus to the page), so it renders aria-disabled and the click does nothing.
 */
export function useLikeControl(recipeId: string) {
  const { status } = useAuth();
  const { isLiked, isPending, toggle, ready } = useLikedIds();
  const pending = isPending(recipeId);
  const waiting = status === 'loading' || (status === 'ready' && !ready);
  return {
    liked: isLiked(recipeId),
    disabled: waiting,
    pending,
    toggle: () => {
      if (pending) return;
      toggle(recipeId);
    },
  };
}
