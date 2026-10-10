export const CUISINES = [
  'italian', 'mexican', 'chinese', 'indian', 'greek',
  'french', 'japanese', 'thai', 'spanish', 'american',
];

const DIETS = [
  ['vegan', 'Vegan'],
  ['vegetarian', 'Vegetarian'],
  ['glutenFree', 'Gluten free'],
  ['dairyFree', 'Dairy free'],
] as const;

export interface FilterValues {
  cuisine: string;
  vegan: boolean;
  vegetarian: boolean;
  glutenFree: boolean;
  dairyFree: boolean;
  maxReadyInMinutes: string;
  minRating: string;
}

export function FilterBar({
  values,
  onChange,
}: {
  values: FilterValues;
  onChange: (patch: Partial<FilterValues>) => void;
}) {
  return (
    <div className="mb-6 flex flex-wrap items-center gap-2 border-b border-hairline pb-4">
      <label className="text-xs font-bold tracking-widest uppercase">
        Cuisine{' '}
        <select
          aria-label="Cuisine"
          value={values.cuisine}
          onChange={(e) => onChange({ cuisine: e.target.value })}
          className="ml-1 border border-hairline px-2 py-1 text-sm font-normal normal-case"
        >
          <option value="">All</option>
          {CUISINES.map((c) => (
            <option key={c} value={c}>{c}</option>
          ))}
        </select>
      </label>

      {DIETS.map(([key, label]) => (
        <button
          key={key}
          onClick={() => onChange({ [key]: !values[key] } as Partial<FilterValues>)}
          className={`border px-3 py-1 text-xs font-bold tracking-wide uppercase ${
            values[key] ? 'border-ink bg-ink text-white' : 'border-hairline hover:border-ink'
          }`}
        >
          {label}
        </button>
      ))}

      <label className="text-xs font-bold tracking-widest uppercase">
        Max time{' '}
        <select
          aria-label="Max time"
          value={values.maxReadyInMinutes}
          onChange={(e) => onChange({ maxReadyInMinutes: e.target.value })}
          className="ml-1 border border-hairline px-2 py-1 text-sm font-normal normal-case"
        >
          <option value="">Any</option>
          {['15', '30', '45', '60'].map((m) => (
            <option key={m} value={m}>{m} min</option>
          ))}
        </select>
      </label>

      <label className="text-xs font-bold tracking-widest uppercase">
        Min rating{' '}
        <select
          aria-label="Min rating"
          value={values.minRating}
          onChange={(e) => onChange({ minRating: e.target.value })}
          className="ml-1 border border-hairline px-2 py-1 text-sm font-normal normal-case"
        >
          <option value="">Any</option>
          <option value="3">3+</option>
          <option value="4">4+</option>
          <option value="4.5">4.5+</option>
        </select>
      </label>
    </div>
  );
}
