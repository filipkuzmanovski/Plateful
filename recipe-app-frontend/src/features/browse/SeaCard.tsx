import type { SeaInfo } from '@/api/cuisines';

const ROWS = [
  { label: 'Vibe', field: 'vibe' },
  { label: 'Star ingredients', field: 'starIngredients' },
  { label: 'Icon dishes', field: 'iconDishes' },
] as const;

/** A short field guide to one sea, shown under the Seas chips while that sea is ticked. */
export function SeaCard({ sea }: { sea: SeaInfo }) {
  return (
    <section aria-label={sea.label} className="animate-view-in rounded-card border border-rule bg-surface p-4">
      <h4 className="mb-3 font-serif text-lg leading-tight font-semibold tracking-[-0.015em] text-ink">{sea.label}</h4>
      <dl className="space-y-2.5">
        {ROWS.map((row) => (
          <div key={row.field}>
            <dt className="text-[13px] font-semibold text-ink-soft">{row.label}</dt>
            <dd className="mt-0.5 text-[15px] leading-snug text-ink">{sea[row.field]}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
