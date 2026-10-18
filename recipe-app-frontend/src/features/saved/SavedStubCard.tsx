import { Link } from 'react-router';
import { EyeOff } from 'lucide-react';
import type { HiddenState } from '@/lib/types';
import { BookmarkButton } from './BookmarkButton';

const BADGES: Record<HiddenState, { label: string; className: string }> = {
  under_review: { label: 'Under review', className: 'bg-saffron/15 text-saffron-deep' },
  unavailable: { label: 'Unavailable', className: 'bg-sand text-olive/70' },
};

/**
 * A saved recipe that is hidden right now: it keeps its place in the list under the title it last had
 * while approved, in the same footprint as RecipeCard. The bookmark is a sibling of the link, never inside it.
 */
export function SavedStubCard({ id, approvedTitle, state }: { id: string; approvedTitle: string; state: HiddenState }) {
  const { label, className } = BADGES[state];
  return (
    <div className="group relative flex transition duration-300 ease-out hover:-translate-y-1">
      <Link
        to={`/recipes/${id}`}
        className="flex flex-1 flex-col overflow-hidden rounded-card bg-paper shadow-card ring-1 ring-sand/70 transition duration-300 ease-out group-hover:shadow-lift group-hover:ring-sand"
      >
        <div className="aspect-[4/3] overflow-hidden bg-sand">
          <div className="flex size-full items-center justify-center bg-sand text-olive/30">
            <EyeOff aria-hidden="true" className="size-10" strokeWidth={1.25} />
          </div>
        </div>
        <div className="flex flex-1 flex-col gap-2.5 px-4 pt-3.5 pb-4">
          <h3 className="line-clamp-2 font-serif text-lg leading-snug font-semibold text-olive transition-colors group-hover:text-terracotta">
            {approvedTitle}
          </h3>
          <div className="mt-auto">
            <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${className}`}>
              {label}
            </span>
          </div>
        </div>
      </Link>
      <BookmarkButton recipeId={id} title={approvedTitle} />
    </div>
  );
}
