import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { ChevronRight, ClipboardCheck } from 'lucide-react';
import { getPendingRecipes, isAdminMfaSetupRequired } from '@/api/admin';
import { Pagination } from '@/components/Pagination';
import { EmptyState, Pill, Spinner } from '@/components/ui';
import type { RecipeDisplay } from '@/lib/types';
import { AdminMfaPanel } from './AdminMfaPanel';

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * 24 * 3600],
  ['month', 30 * 24 * 3600],
  ['day', 24 * 3600],
  ['hour', 3600],
  ['minute', 60],
];

/** "3 days ago", "2 hours ago", "now". Null for an unreadable date. */
function timeAgo(iso: string): string | null {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return null;
  const seconds = Math.round((then - Date.now()) / 1000);
  const rtf = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
  for (const [unit, size] of UNITS) {
    if (Math.abs(seconds) >= size) return rtf.format(Math.trunc(seconds / size), unit);
  }
  return rtf.format(0, 'second');
}

function QueueRow({ recipe }: { recipe: RecipeDisplay }) {
  const review = recipe.review;
  const submitted = review ? timeAgo(review.submittedAt) : null;
  return (
    <li>
      <Link
        to={`/admin/review/${recipe.id}`}
        className="group flex items-center gap-4 rounded-card bg-paper px-5 py-4 shadow-card ring-1 ring-sand/70 transition hover:-translate-y-px hover:ring-olive/30"
      >
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-2">
            <span className="font-serif text-xl leading-tight font-semibold text-olive">{recipe.title}</span>
            {review?.everApproved && <Pill tone="saffron" className="font-semibold">Re-review</Pill>}
          </span>
          <span className="mt-1 block text-sm text-olive/70">
            {recipe.user && <>by {recipe.user.firstName} {recipe.user.lastName}</>}
            {recipe.user && submitted && ' · '}
            {review && submitted && <time dateTime={review.submittedAt}>submitted {submitted}</time>}
          </span>
        </span>
        <ChevronRight aria-hidden="true" className="size-5 shrink-0 text-olive/40 transition group-hover:text-olive" />
      </Link>
    </li>
  );
}

/** Recipes waiting for a decision, oldest first. */
export default function ReviewQueuePage() {
  const [page, setPage] = useState(0);
  const queue = useQuery({ queryKey: ['admin', 'pending', page], queryFn: () => getPendingRecipes(page), retry: false });

  // Deciding the last recipe of a later page leaves it empty: step back to the last page that has any.
  const emptyLaterPage = !!queue.data && queue.data.content.length === 0 && page > 0;
  const lastPage = queue.data ? Math.max(0, queue.data.page.totalPages - 1) : 0;
  useEffect(() => {
    if (emptyLaterPage) setPage(Math.min(page - 1, lastPage));
  }, [emptyLaterPage, page, lastPage]);

  if (queue.isPending || emptyLaterPage) return <Spinner />;
  if (queue.isError) {
    return isAdminMfaSetupRequired(queue.error) ? (
      <AdminMfaPanel />
    ) : (
      <p className="text-sm text-danger">Couldn't load the review queue.</p>
    );
  }

  return (
    <>
      <h1 className="font-serif text-4xl leading-[1.05] font-semibold tracking-tight text-olive sm:text-5xl">
        Review queue
      </h1>
      {queue.data.content.length === 0 ? (
        <EmptyState icon={ClipboardCheck}>Nothing to review right now.</EmptyState>
      ) : (
        <>
          <ul className="mt-8 space-y-3">
            {queue.data.content.map((recipe) => (
              <QueueRow key={recipe.id} recipe={recipe} />
            ))}
          </ul>
          <Pagination page={page} totalPages={queue.data.page.totalPages} onPage={setPage} />
        </>
      )}
    </>
  );
}
