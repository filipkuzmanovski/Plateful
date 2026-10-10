import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ApiError } from '@/api/client';
import { deleteMyRating, getMyRating, putMyRating } from '@/api/ratings';
import { useAuth } from '@/auth/AuthProvider';

export function RatingBox({ recipeId }: { recipeId: string }) {
  const { status, profile } = useAuth();
  const queryClient = useQueryClient();

  const myRating = useQuery({
    queryKey: ['myRating', recipeId, profile?.id],
    queryFn: () => getMyRating(recipeId),
    enabled: status === 'ready',
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['myRating', recipeId] });
    void queryClient.invalidateQueries({ queryKey: ['recipe', recipeId] });
    void queryClient.invalidateQueries({ queryKey: ['recipes'] });
    void queryClient.invalidateQueries({ queryKey: ['userRecipes'] });
  };
  const onError = (e: unknown) => toast.error(e instanceof ApiError ? e.detail : 'Something went wrong');
  const rate = useMutation({ mutationFn: (n: number) => putMyRating(recipeId, n), onSuccess: invalidate, onError });
  const unrate = useMutation({ mutationFn: () => deleteMyRating(recipeId), onSuccess: invalidate, onError });

  if (status !== 'ready') {
    return <p className="text-xs text-muted">Sign in to rate this recipe.</p>;
  }
  // Wait for the current rating before showing stars, so a click can't race the load
  // (clicking the already-rated star must un-rate, not re-PUT).
  if (myRating.isPending) {
    return <p className="text-xs text-muted">Loading your rating…</p>;
  }
  const current = myRating.data ?? null;

  return (
    <div>
      <p className="mb-1 text-xs font-bold tracking-widest uppercase">Your rating</p>
      <div className="flex gap-1">
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            aria-label={`Rate ${n} stars`}
            aria-pressed={current === n}
            disabled={rate.isPending || unrate.isPending}
            onClick={() => (current === n ? unrate.mutate() : rate.mutate(n))}
            className={`text-2xl leading-none ${
              current !== null && n <= current ? 'text-accent' : 'text-hairline hover:text-accent'
            }`}
          >
            ★
          </button>
        ))}
      </div>
      {current !== null && <p className="mt-1 text-xs text-muted">Click your star again to remove.</p>}
    </div>
  );
}
