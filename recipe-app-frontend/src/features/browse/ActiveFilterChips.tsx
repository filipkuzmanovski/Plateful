import { X } from 'lucide-react';
import type { CuisineInfo } from '@/api/cuisines';
import type { FilterState } from './filterParams';
import { DIETS, humanizeKey, regionLabel } from './regions';

interface ActiveChip {
  id: string;
  label: string;
  remove: (s: FilterState) => FilterState;
}

function chipsFor(state: FilterState, cuisines: CuisineInfo[] | undefined): ActiveChip[] {
  const labelOf = (key: string) => cuisines?.find((c) => c.key === key)?.label ?? humanizeKey(key);
  return [
    ...state.region.map((key) => ({
      id: `region:${key}`,
      label: regionLabel(key),
      remove: (s: FilterState) => ({ ...s, region: s.region.filter((k) => k !== key) }),
    })),
    ...state.cuisine.map((key) => ({
      id: `cuisine:${key}`,
      label: labelOf(key),
      remove: (s: FilterState) => ({ ...s, cuisine: s.cuisine.filter((k) => k !== key) }),
    })),
    ...DIETS.filter((d) => state[d.key]).map((d) => ({
      id: d.key,
      label: d.label,
      remove: (s: FilterState) => ({ ...s, [d.key]: false }),
    })),
    ...(state.maxReadyInMinutes !== null
      ? [{ id: 'time', label: `Up to ${state.maxReadyInMinutes} min`, remove: (s: FilterState) => ({ ...s, maxReadyInMinutes: null }) }]
      : []),
    ...(state.minRating !== null
      ? [{ id: 'rating', label: `Rated ${state.minRating}+`, remove: (s: FilterState) => ({ ...s, minRating: null }) }]
      : []),
  ];
}

/** One removable chip per applied filter value, shown beside the Filters button. */
export function ActiveFilterChips({
  state,
  cuisines,
  onChange,
}: {
  state: FilterState;
  cuisines: CuisineInfo[] | undefined;
  onChange: (s: FilterState) => void;
}) {
  const chips = chipsFor(state, cuisines);
  if (chips.length === 0) return null;
  return (
    <ul aria-label="Active filters" className="flex flex-wrap items-center gap-2">
      {chips.map((chip) => (
        <li key={chip.id}>
          <button
            type="button"
            aria-label={`Remove ${chip.label}`}
            onClick={() => onChange(chip.remove(state))}
            className="group inline-flex items-center gap-1.5 rounded-full border border-olive/15 bg-olive/[0.07] py-1.5 pr-2 pl-3.5 text-sm font-medium text-olive transition hover:border-olive/30 hover:bg-olive/[0.12] focus-visible:outline-olive active:scale-[0.97]"
          >
            {chip.label}
            <span className="flex size-5 items-center justify-center rounded-full bg-olive/10 transition group-hover:bg-olive group-hover:text-cream">
              <X aria-hidden="true" className="size-3" strokeWidth={2.75} />
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}
