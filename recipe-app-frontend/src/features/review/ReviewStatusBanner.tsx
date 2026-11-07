import { Link } from 'react-router';
import type { RecipeReview } from '@/lib/types';

/** Tells the author why their recipe isn't showing: hidden after reports (accent) or not published (danger). Live recipes get none. */
export function ReviewStatusBanner({ review, recipeId }: { review: RecipeReview; recipeId: string }) {
  if (review.hidden) {
    return (
      <div role="status" className="mb-5 rounded-card bg-accent/15 px-4 py-3 text-[15px] font-medium text-accent-ink">
        Hidden while an admin looks at reports.
      </div>
    );
  }

  if (review.status !== 'rejected') return null;

  return (
    <div role="status" className="mb-5 rounded-card bg-danger/10 px-4 py-3 text-[15px] text-danger">
      <p className="font-semibold">Not published. Edit it to publish it.</p>
      {review.rejectionReason && <p className="mt-1 whitespace-pre-line">{review.rejectionReason}</p>}
      <Link
        to={`/recipes/${recipeId}/edit`}
        className="mt-3 inline-flex min-h-11 items-center rounded-full border border-danger/50 bg-surface px-4 text-[15px] font-semibold text-danger transition-colors hover:bg-danger hover:text-on-danger"
      >
        Edit and resubmit
      </Link>
    </div>
  );
}
