import { Link } from 'react-router';
import type { CookbookSummary } from '@/lib/types';
import { coverClasses, DEFAULT_SUBTITLE } from './constants';

/** One book on the shelf: a small cover that opens the book. */
export function CoverThumb({ book, ownerName }: { book: CookbookSummary; ownerName: string }) {
  return (
    <Link
      to={`/cookbooks/${book.id}`}
      className={`flex aspect-[3/4] flex-col justify-between rounded-md border border-rule p-3 text-center font-serif transition-[filter] duration-200 hover:brightness-110 focus-visible:brightness-110 ${coverClasses(book.volume)}`}
    >
      <span className="font-sans text-[11px] tracking-[0.12em] uppercase">Volume {book.volume}</span>
      <span className="border-y border-on-cover/60 py-2 text-lg leading-tight font-medium break-words italic">
        {ownerName}
      </span>
      <span className="line-clamp-2 text-[13px] break-words">{book.subtitle ?? DEFAULT_SUBTITLE}</span>
    </Link>
  );
}
