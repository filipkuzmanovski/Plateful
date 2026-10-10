import type { RecipeFilter } from '@/lib/types';

export interface FilterState {
  cuisine: string[];
  region: string[];
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
  return {
    cuisine: readList(params, 'cuisine'),
    region: readList(params, 'region'),
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
  for (const key of ['cuisine', 'region', ...FLAGS, 'maxReadyInMinutes', 'minRating', 'page']) {
    out.delete(key);
  }
  for (const c of state.cuisine) out.append('cuisine', c);
  for (const r of state.region) out.append('region', r);
  for (const flag of FLAGS) if (state[flag]) out.set(flag, 'true');
  if (state.maxReadyInMinutes !== null) out.set('maxReadyInMinutes', String(state.maxReadyInMinutes));
  if (state.minRating !== null) out.set('minRating', String(state.minRating));
  return out;
}

export function toApiFilter(state: FilterState): RecipeFilter {
  const filter: RecipeFilter = {};
  if (state.cuisine.length > 0) filter.cuisine = state.cuisine;
  if (state.region.length > 0) filter.region = state.region;
  for (const flag of FLAGS) if (state[flag]) filter[flag] = true;
  if (state.maxReadyInMinutes !== null) filter.maxReadyInMinutes = state.maxReadyInMinutes;
  if (state.minRating !== null) filter.minRating = state.minRating;
  return filter;
}

export function countActive(state: FilterState): number {
  return (
    state.cuisine.length +
    state.region.length +
    FLAGS.filter((f) => state[f]).length +
    (state.maxReadyInMinutes !== null ? 1 : 0) +
    (state.minRating !== null ? 1 : 0)
  );
}
