import type { ReviewStatus } from '@/lib/types';
import { Pill } from '@/components/ui';

const BADGES = {
  pending: { label: 'Pending review', tone: 'accent' },
  rejected: { label: 'Rejected', tone: 'danger' },
} as const;

/** "Pending review" (accent) or "Rejected" (danger). Approved recipes carry no badge. */
export function StatusBadge({ status, className = '' }: { status: ReviewStatus; className?: string }) {
  if (status === 'approved') return null;
  const { label, tone } = BADGES[status];
  return (
    <Pill tone={tone} className={`font-semibold ${className}`}>
      {label}
    </Pill>
  );
}
