import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Star } from 'lucide-react';
import { ApiError } from '@/api/client';
import { deleteMyRating, getMyRating, putMyRating } from '@/api/ratings';
import { useAuth } from '@/auth/AuthProvider';

export function RatingBox({ recipeId }: { recipeId: string }) {
  const { status, profile } = useAuth();
  const queryClient = useQueryClient();
  const [hovered, setHovered] = useState<number | null>(null); // visual preview only

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
    void queryClient.invalidateQueries({ queryKey: ['savedRecipes'] });
  };
  const onError = (e: unknown) => toast.error(e instanceof ApiError ? e.detail : 'Something went wrong');
  const rate = useMutation({ mutationFn: (n: number) => putMyRating(recipeId, n), onSuccess: invalidate, onError });
  const unrate = useMutation({ mutationFn: () => deleteMyRating(recipeId), onSuccess: invalidate, onError });

  if (status !== 'ready') {
    return <p className="text-[15px] text-ink-soft">Sign in to rate this recipe.</p>;
  }
  // Wait for the current rating before showing stars, so a click can't race the load
  // (clicking the already-rated star must un-rate, not re-PUT).
  if (myRating.isPending) {
    return <p className="text-[15px] text-ink-soft">Loading your rating…</p>;
  }
  const current = myRating.data ?? null;
  const shown = hovered ?? current;

  return (
    <div>
      <p className="mb-1.5 text-[13px] font-semibold text-ink-soft">Your rating</p>
      <div className="-ml-1.5 flex" onMouseLeave={() => setHovered(null)}>
        {[1, 2, 3, 4, 5].map((n) => {
          const lit = shown !== null && n <= shown;
          return (
            <button
              key={n}
              aria-label={`Rate ${n} stars`}
              aria-pressed={current === n}
              disabled={rate.isPending || unrate.isPending}
              onClick={() => (current === n ? unrate.mutate() : rate.mutate(n))}
              onMouseEnter={() => setHovered(n)}
              className="flex size-11 items-center justify-center rounded-full transition-colors hover:bg-tint disabled:opacity-50"
            >
              <Star
                aria-hidden="true"
                strokeWidth={1.5}
                className={`size-7 transition-colors duration-150 ${
                  lit ? 'fill-accent text-accent-strong' : 'fill-transparent text-field'
                }`}
              />
            </button>
          );
        })}
      </div>
      {current !== null && <p className="mt-1 text-[13px] text-ink-soft">Click your star again to remove.</p>}
    </div>
  );
}
