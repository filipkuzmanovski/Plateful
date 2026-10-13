export interface Region {
  key: string;
  label: string;
}

/** Region chips in the filter drawer. Keys match the backend's `region` filter values. */
export const REGIONS: readonly Region[] = [
  { key: 'europe', label: 'Europe' },
  { key: 'asia', label: 'Asia' },
  { key: 'americas', label: 'Americas' },
  { key: 'africa', label: 'Africa' },
  { key: 'middle_east', label: 'Middle East' },
  { key: 'oceania', label: 'Oceania' },
];

/** Sea chips before the seas endpoint has loaded. Order matches the backend's seas.json. */
export const SEAS_FALLBACK: readonly Region[] = [
  { key: 'mediterranean', label: 'Mediterranean' },
  { key: 'caribbean', label: 'Caribbean' },
  { key: 'north_sea', label: 'North Sea' },
  { key: 'pacific', label: 'Sea of Japan & Pacific' },
  { key: 'gulf_of_mexico', label: 'Gulf of Mexico' },
];

/** Dish-type toggles, in drawer order. Keys are FilterState flags. */
export const DIETS = [
  { key: 'vegan', label: 'Vegan' },
  { key: 'vegetarian', label: 'Vegetarian' },
  { key: 'glutenFree', label: 'Gluten free' },
  { key: 'dairyFree', label: 'Dairy free' },
] as const;

/** Fallback label for a key the catalogue hasn't supplied (still loading, or unknown). */
export const humanizeKey = (key: string) => key.replace(/[-_]/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());

export const regionLabel = (key: string) => REGIONS.find((r) => r.key === key)?.label ?? humanizeKey(key);

export const seaLabel = (key: string) => SEAS_FALLBACK.find((s) => s.key === key)?.label ?? humanizeKey(key);
