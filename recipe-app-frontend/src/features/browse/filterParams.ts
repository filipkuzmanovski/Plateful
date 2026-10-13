import type { RecipeFilter } from '@/lib/types';

export interface FilterState {
  cuisine: string[];
  region: string[];
  sea: string[];
  vegan: boolean;
  vegetarian: boolean;
  glutenFree: boolean;
  dairyFree: boolean;
  maxReadyInMinutes: number | null;
  minRating: number | null;
}

export const emptyFilters: FilterState = {
  cuisine: [],
  region: [],
  sea: [],
  vegan: false,
  vegetarian: false,
  glutenFree: false,
  dairyFree: false,
  maxReadyInMinutes: null,
  minRating: null,
};

const FLAGS = ['vegan', 'vegetarian', 'glutenFree', 'dairyFree'] as const;

function readList(params: URLSearchParams, key: string): string[] {
  return [...new Set(params.getAll(key).filter((v) => v !== ''))];
}

/** Integer 1-600, otherwise null. */
export function readMinutes(raw: string | null): number | null {
  if (raw === null || !/^\d+$/.test(raw)) return null;
  const n = Number(raw);
  return n >= 1 && n <= 600 ? n : null;
}

/** 1-5 in 0.5 steps, otherwise null. */
export function readRating(raw: string | null): number | null {
  if (raw === null || raw.trim() === '') return null;
  const n = Number(raw);
  return Number.isFinite(n) && n >= 1 && n <= 5 && Number.isInteger(n * 2) ? n : null;
}

export function readFilters(params: URLSearchParams): FilterState {
  // Old links carry region=mediterranean; Mediterranean is a sea now.
  const regionValues = readList(params, 'region');
  const legacySea = regionValues.includes('mediterranean') ? ['mediterranean'] : [];
  return {
    cuisine: readList(params, 'cuisine'),
    region: regionValues.filter((r) => r !== 'mediterranean'),
    sea: [...new Set([...readList(params, 'sea'), ...legacySea])],
    vegan: params.get('vegan') === 'true',
    vegetarian: params.get('vegetarian') === 'true',
    glutenFree: params.get('glutenFree') === 'true',
    dairyFree: params.get('dairyFree') === 'true',
    maxReadyInMinutes: readMinutes(params.get('maxReadyInMinutes')),
    minRating: readRating(params.get('minRating')),
  };
}

/** Returns a new URLSearchParams: base with all filter keys replaced and `page` removed. */
export function writeFilters(state: FilterState, base?: URLSearchParams): URLSearchParams {
  const out = new URLSearchParams(base);
  for (const key of ['cuisine', 'region', 'sea', ...FLAGS, 'maxReadyInMinutes', 'minRating', 'page']) {
    out.delete(key);
  }
  for (const c of state.cuisine) out.append('cuisine', c);
  for (const r of state.region) out.append('region', r);
  for (const s of state.sea) out.append('sea', s);
  for (const flag of FLAGS) if (state[flag]) out.set(flag, 'true');
  if (state.maxReadyInMinutes !== null) out.set('maxReadyInMinutes', String(state.maxReadyInMinutes));
  if (state.minRating !== null) out.set('minRating', String(state.minRating));
  return out;
}

export function toApiFilter(state: FilterState): RecipeFilter {
  const filter: RecipeFilter = {};
  if (state.cuisine.length > 0) filter.cuisine = state.cuisine;
  if (state.region.length > 0) filter.region = state.region;
  if (state.sea.length > 0) filter.sea = state.sea;
  for (const flag of FLAGS) if (state[flag]) filter[flag] = true;
  if (state.maxReadyInMinutes !== null) filter.maxReadyInMinutes = state.maxReadyInMinutes;
  if (state.minRating !== null) filter.minRating = state.minRating;
  return filter;
}

export function countActive(state: FilterState): number {
  return (
    state.cuisine.length +
    state.region.length +
    state.sea.length +
    FLAGS.filter((f) => state[f]).length +
    (state.maxReadyInMinutes !== null ? 1 : 0) +
    (state.minRating !== null ? 1 : 0)
  );
}

export const SORT_KEYS = ['newest', 'most-rated', 'top-rated', 'most-comments'] as const;
export type SortKey = (typeof SORT_KEYS)[number];

export const SORT_LABELS: Record<SortKey, string> = {
  newest: 'Newest',
  'most-rated': 'Most rated',
  'top-rated': 'Most popular',
  'most-comments': 'Most comments',
};

/** The `sort` URL param; anything unknown falls back to newest. */
export function readSort(params: URLSearchParams): SortKey {
  const raw = params.get('sort');
  return SORT_KEYS.find((k) => k === raw) ?? 'newest';
}

/** Returns a new URLSearchParams with `sort` replaced (omitted for newest) and `page` removed. */
export function writeSort(sort: SortKey, base?: URLSearchParams): URLSearchParams {
  const out = new URLSearchParams(base);
  out.delete('sort');
  out.delete('page');
  if (sort !== 'newest') out.set('sort', sort);
  return out;
}
