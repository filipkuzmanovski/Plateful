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
          className="inline-flex items-center justify-center rounded-full bg-olive px-5 py-2.5 text-sm font-semibold whitespace-nowrap text-cream shadow-button transition duration-200 ease-out hover:-translate-y-px hover:bg-olive/90 hover:shadow-lift"
        >
          Open two-factor settings
        </Link>
      }
    >
      Set up two-factor authentication to use the review queue.
    </EmptyState>
  );
}
