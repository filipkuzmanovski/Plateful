import {
  countActive,
  emptyFilters,
  readFilters,
  toApiFilter,
  writeFilters,
  type FilterState,
} from './filterParams';

const q = (s: string) => new URLSearchParams(s);

test('readsRepeatedCuisineAndRegion', () => {
  const state = readFilters(q('?cuisine=italian&cuisine=greek&region=asia'));
  expect(state.cuisine).toEqual(['italian', 'greek']);
  expect(state.region).toEqual(['asia']);
});

test('dedupesRepeatedValues', () => {
  const state = readFilters(q('?cuisine=italian&cuisine=italian&cuisine=greek&region=asia&region=asia'));
  expect(state.cuisine).toEqual(['italian', 'greek']);
  expect(state.region).toEqual(['asia']);
});

test('rejectsOutOfRangeNumbers', () => {
  expect(readFilters(q('maxReadyInMinutes=0')).maxReadyInMinutes).toBeNull();
  expect(readFilters(q('maxReadyInMinutes=601')).maxReadyInMinutes).toBeNull();
  expect(readFilters(q('maxReadyInMinutes=abc')).maxReadyInMinutes).toBeNull();
  expect(readFilters(q('maxReadyInMinutes=30')).maxReadyInMinutes).toBe(30);
  expect(readFilters(q('minRating=7')).minRating).toBeNull();
  expect(readFilters(q('minRating=3.3')).minRating).toBeNull();
  expect(readFilters(q('minRating=3.5')).minRating).toBe(3.5);
});

test('writeOmitsUnsetAndResetsPage', () => {
  const base = q('page=3&q=keep');
  const out = writeFilters({ ...emptyFilters, cuisine: ['thai'], vegan: true }, base);
  expect(out.has('page')).toBe(false);
  expect(out.get('q')).toBe('keep');
  expect(out.getAll('cuisine')).toEqual(['thai']);
  expect(out.get('vegan')).toBe('true');
  expect(out.has('vegetarian')).toBe(false);
  expect(out.has('glutenFree')).toBe(false);
  expect(out.has('dairyFree')).toBe(false);
  expect(out.has('maxReadyInMinutes')).toBe(false);
  expect(out.has('minRating')).toBe(false);
  expect(out.has('region')).toBe(false);
});

test('roundTrips', () => {
  const state: FilterState = {
    cuisine: ['italian', 'greek'],
    region: ['europe'],
    vegan: true,
    vegetarian: false,
    glutenFree: true,
    dairyFree: false,
    maxReadyInMinutes: 45,
    minRating: 4.5,
  };
  expect(readFilters(writeFilters(state))).toEqual(state);
  expect(readFilters(writeFilters(emptyFilters))).toEqual(emptyFilters);
});

test('countActive counts each value', () => {
  const state: FilterState = {
    ...emptyFilters,
    cuisine: ['italian', 'greek'],
    region: ['asia'],
    vegan: true,
    minRating: 4,
  };
  expect(countActive(state)).toBe(5);
  expect(countActive(emptyFilters)).toBe(0);
});

test('toApiFilter omits unset values', () => {
  expect(toApiFilter(emptyFilters)).toEqual({});
  expect(toApiFilter({ ...emptyFilters, cuisine: ['thai'], vegan: true, maxReadyInMinutes: 20 })).toEqual({
    cuisine: ['thai'],
    vegan: true,
    maxReadyInMinutes: 20,
  });
});
