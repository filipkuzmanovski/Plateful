import type { ReviewStatus } from '@/lib/types';

const BADGES = {
  pending: { label: 'Pending review', className: 'bg-saffron/15 text-saffron-deep' },
  rejected: { label: 'Rejected', className: 'bg-danger/10 text-danger' },
} as const;

/** "Pending review" (saffron) or "Rejected" (danger). Approved recipes carry no badge. */
export function StatusBadge({ status, className = '' }: { status: ReviewStatus; className?: string }) {
  if (status === 'approved') return null;
  const { label, className: tone } = BADGES[status];
  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${tone} ${className}`}
    >
      {label}
    </span>
  );
}
