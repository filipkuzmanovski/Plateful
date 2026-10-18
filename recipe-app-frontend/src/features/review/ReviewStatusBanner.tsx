import { Link } from 'react-router';
import type { RecipeReview } from '@/lib/types';

/** Tells the author why their recipe is hidden: waiting for review (saffron) or rejected with the reason (danger). */
export function ReviewStatusBanner({ review, recipeId }: { review: RecipeReview; recipeId: string }) {
  if (review.status === 'approved') return null;

  if (review.status === 'pending') {
    return (
      <div role="status" className="mb-5 rounded-2xl border border-saffron/40 bg-saffron/15 px-4 py-3 text-sm font-medium text-saffron-deep">
        Waiting for review. Only you can see this recipe.
      </div>
    );
  }

  return (
    <div role="status" className="mb-5 rounded-2xl border border-danger/35 bg-danger/10 px-4 py-3 text-sm text-danger">
      <p className="font-semibold">This recipe wasn't approved.</p>
      {review.rejectionReason && <p className="mt-1 whitespace-pre-line">{review.rejectionReason}</p>}
      <Link
        to={`/recipes/${recipeId}/edit`}
        className="mt-3 inline-flex items-center rounded-full border border-danger/35 bg-paper px-4 py-2 text-sm font-semibold text-danger transition hover:border-danger hover:bg-danger hover:text-paper"
      >
        Edit and resubmit
      </Link>
    </div>
  );
}
