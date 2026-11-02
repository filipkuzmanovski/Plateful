import { Star } from 'lucide-react';

export function StarDisplay({ average, count }: { average: number | null; count: number }) {
  if (average === null) return <span className="text-ink-soft italic">No ratings yet</span>;
  return (
    <span className="inline-flex items-center gap-1 font-semibold text-ink tabular-nums">
      <Star aria-hidden="true" className="size-[1.05em] shrink-0 fill-accent text-accent" strokeWidth={1.5} />
      {average.toFixed(1)} <span className="font-normal text-ink-soft">({count})</span>
    </span>
  );
}
