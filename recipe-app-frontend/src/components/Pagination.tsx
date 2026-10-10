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

const navBtn =
  'h-9 border border-hairline px-3 text-sm font-bold hover:border-ink disabled:opacity-40 disabled:hover:border-hairline';

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
    <nav aria-label="Pagination" className="mt-8 flex flex-wrap justify-center gap-1">
      <button aria-label="Previous page" disabled={page <= 0} onClick={() => onPage(page - 1)} className={navBtn}>
        ‹
      </button>
      {pageWindow(page, totalPages).map((entry, idx) =>
        entry === 'gap' ? (
          <span
            key={`gap-${idx}`}
            aria-hidden="true"
            className="flex h-9 w-6 items-center justify-center text-sm text-muted"
          >
            …
          </span>
        ) : (
          <button
            key={entry}
            aria-label={`Go to page ${entry + 1}`}
            aria-current={entry === page ? 'page' : undefined}
            onClick={() => onPage(entry)}
            className={`h-9 w-9 border text-sm font-bold ${
              entry === page ? 'border-ink bg-ink text-white' : 'border-hairline hover:border-ink'
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
        ›
      </button>
    </nav>
  );
}
