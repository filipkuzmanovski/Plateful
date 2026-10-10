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
    <nav className="mt-8 flex justify-center gap-1">
      {Array.from({ length: totalPages }, (_, i) => (
        <button
          key={i}
          aria-label={`Go to page ${i + 1}`}
          aria-current={i === page ? 'page' : undefined}
          onClick={() => onPage(i)}
          className={`h-9 w-9 border text-sm font-bold ${
            i === page ? 'border-ink bg-ink text-white' : 'border-hairline hover:border-ink'
          }`}
        >
          {i + 1}
        </button>
      ))}
    </nav>
  );
}
