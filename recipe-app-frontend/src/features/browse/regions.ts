export interface Region {
  key: string;
  label: string;
}

/** Region chips in the filter drawer. Keys match the backend's `region` filter values. */
export const REGIONS: readonly Region[] = [
  { key: 'mediterranean', label: 'Mediterranean' },
  { key: 'europe', label: 'Europe' },
  { key: 'asia', label: 'Asia' },
  { key: 'americas', label: 'Americas' },
  { key: 'africa', label: 'Africa' },
  { key: 'middle_east', label: 'Middle East' },
  { key: 'oceania', label: 'Oceania' },
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
