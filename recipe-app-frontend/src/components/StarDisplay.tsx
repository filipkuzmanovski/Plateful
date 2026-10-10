export function StarDisplay({ average, count }: { average: number | null; count: number }) {
  if (average === null) return <span className="text-sm text-muted">No ratings yet</span>;
  return (
    <span className="text-sm font-semibold text-accent">
      ★ {average.toFixed(1)} <span className="font-normal text-muted">({count})</span>
    </span>
  );
}
