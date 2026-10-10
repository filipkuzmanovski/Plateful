import { Star } from 'lucide-react';

export function StarDisplay({ average, count }: { average: number | null; count: number }) {
  if (average === null) return <span className="text-sm text-olive/70 italic">No ratings yet</span>;
  return (
    <span className="inline-flex items-center gap-1 text-sm font-semibold text-olive tabular-nums">
      <Star aria-hidden="true" className="size-[1.05em] shrink-0 fill-saffron text-saffron" strokeWidth={1.5} />
      {average.toFixed(1)} <span className="font-normal text-olive/70">({count})</span>
    </span>
  );
}
