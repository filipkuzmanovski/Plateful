import { Link } from 'react-router';
import { EyeOff } from 'lucide-react';
import type { HiddenState } from '@/lib/types';
import { Pill } from '@/components/ui';
import { BookmarkButton } from './BookmarkButton';

// Pill tones: "accent" is the saffron wash; "tint" carries full-strength `text-ink` (small text on tint needs it for 4.5:1).
const BADGES: Record<HiddenState, { label: string; tone: 'accent' | 'tint' }> = {
  under_review: { label: 'Under review', tone: 'accent' },
  unavailable: { label: 'Unavailable', tone: 'tint' },
};

/**
 * A saved recipe that is hidden right now: it keeps its place in the list under the title it last had
 * while approved, in the same footprint as RecipeCard. The bookmark is a sibling of the link, never inside it.
 */
export function SavedStubCard({ id, approvedTitle, state }: { id: string; approvedTitle: string; state: HiddenState }) {
  const { label, tone } = BADGES[state];
  return (
    <div className="relative flex">
      <Link to={`/recipes/${id}`} className="group flex flex-1 flex-col gap-3">
        <div className="aspect-[4/3] overflow-hidden rounded-md border border-rule bg-tint">
          <div className="flex size-full items-center justify-center text-ink-soft">
            <EyeOff aria-hidden="true" className="size-10" strokeWidth={1.25} />
          </div>
        </div>
        <div className="flex flex-1 flex-col gap-2">
          <h3 className="line-clamp-2 font-serif text-base leading-snug font-semibold tracking-[-0.015em] break-words text-ink decoration-1 underline-offset-4 group-hover:underline group-focus-visible:underline sm:text-lg">
            {approvedTitle}
          </h3>
          <div className="mt-auto">
            <Pill tone={tone}>{label}</Pill>
          </div>
        </div>
      </Link>
      <BookmarkButton recipeId={id} title={approvedTitle} />
    </div>
  );
}
