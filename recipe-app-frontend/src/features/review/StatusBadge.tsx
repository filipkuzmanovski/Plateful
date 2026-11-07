import type { RecipeReview } from '@/lib/types';
import { Pill } from '@/components/ui';

/** "Hidden" (accent) or "Rejected" (danger) for the author's own recipes. Live recipes carry no badge. */
export function StatusBadge({ review, className = '' }: { review: RecipeReview; className?: string }) {
  const badge = review.hidden
    ? ({ label: 'Hidden', tone: 'accent' } as const)
    : review.status === 'rejected'
      ? ({ label: 'Rejected', tone: 'danger' } as const)
      : null;
  if (!badge) return null;
  return (
    <Pill tone={badge.tone} className={`font-semibold ${className}`}>
      {badge.label}
    </Pill>
  );
}
