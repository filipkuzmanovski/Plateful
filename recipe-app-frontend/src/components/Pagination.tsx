import { ChevronLeft, ChevronRight, Ellipsis } from 'lucide-react';

export function pageWindow(page: number, totalPages: number): (number | 'gap')[] {
  const pages = new Set<number>([0, totalPages - 1]);
  for (let i = page - 2; i <= page + 2; i++) if (i >= 0 && i < totalPages) pages.add(i);
  const sorted = [...pages].sort((a, b) => a - b);
  const out: (number | 'gap')[] = [];
  sorted.forEach((p, idx) => {
    if (idx > 0 && p - sorted[idx - 1] > 1) out.push('gap');
    out.push(p);
  });
  return out;
}

const round = 'flex size-10 items-center justify-center rounded-full text-[15px] font-semibold tabular-nums transition-colors';
const navBtn = `${round} text-ink hover:bg-tint disabled:pointer-events-none disabled:opacity-35`;

export function Pagination({
  page,
  totalPages,
  onPage,
}: {
  page: number;
  totalPages: number;
  onPage: (p: number) => void;
}) {
  if (totalPages <= 1) return null;
  return (
    <nav aria-label="Pagination" className="mt-12 flex justify-center">
      <div className="inline-flex max-w-full flex-wrap items-center justify-center gap-0.5 sm:gap-1">
        <button aria-label="Previous page" disabled={page <= 0} onClick={() => onPage(page - 1)} className={navBtn}>
          <ChevronLeft aria-hidden="true" className="size-4" />
        </button>
        {pageWindow(page, totalPages).map((entry, idx) =>
          entry === 'gap' ? (
            <span key={`gap-${idx}`} aria-hidden="true" className="flex h-10 w-6 items-center justify-center text-ink-soft">
              <Ellipsis className="size-4" />
            </span>
          ) : (
            <button
              key={entry}
              aria-label={`Go to page ${entry + 1}`}
              aria-current={entry === page ? 'page' : undefined}
              onClick={() => onPage(entry)}
              className={`${round} ${
                entry === page ? 'bg-ink text-on-ink' : 'text-ink hover:bg-tint'
              }`}
            >
              {entry + 1}
            </button>
          ),
        )}
        <button
          aria-label="Next page"
          disabled={page >= totalPages - 1}
          onClick={() => onPage(page + 1)}
          className={navBtn}
        >
          <ChevronRight aria-hidden="true" className="size-4" />
        </button>
      </div>
    </nav>
  );
}
