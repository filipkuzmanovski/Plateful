import { Link } from 'react-router';
import { ShieldCheck } from 'lucide-react';
import { EmptyState } from '@/components/ui';

/** Shown on both admin pages, in place of the queue, to an admin who has not set up two-factor authentication yet. */
export function AdminMfaPanel() {
  return (
    <EmptyState
      icon={ShieldCheck}
      action={
        <Link
          to="/me"
          className="inline-flex min-h-11 items-center justify-center rounded-full bg-ink px-5 py-2 text-[15px] font-semibold whitespace-nowrap text-on-ink transition-colors duration-200 ease-out hover:bg-ink/90"
        >
          Open two-factor settings
        </Link>
      }
    >
      Set up two-factor authentication to use the review queue.
    </EmptyState>
  );
}
