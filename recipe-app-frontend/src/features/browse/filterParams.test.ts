import {
  countActive,
  emptyFilters,
  readFilters,
  readSort,
  toApiFilter,
  writeFilters,
  writeSort,
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
    sea: ['caribbean', 'pacific'],
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

test('readFilters maps legacy region=mediterranean to sea', () => {
  const state = readFilters(q('region=mediterranean&region=asia'));
  expect(state.sea).toEqual(['mediterranean']);
  expect(state.region).toEqual(['asia']);
});

test('readFilters does not duplicate mediterranean across region and sea', () => {
  const state = readFilters(q('region=mediterranean&sea=mediterranean&sea=pacific'));
  expect(state.sea).toEqual(['mediterranean', 'pacific']);
  expect(state.region).toEqual([]);
});

test('readFilters reads repeated sea', () => {
  expect(readFilters(q('sea=caribbean&sea=pacific&sea=caribbean')).sea).toEqual(['caribbean', 'pacific']);
});

test('writeFilters writes sea, keeps sort and drops page', () => {
  const out = writeFilters({ ...emptyFilters, sea: ['caribbean'] }, q('sort=most-rated&page=3'));
  expect(out.toString()).toBe('sort=most-rated&sea=caribbean');
});

test('countActive and toApiFilter include sea', () => {
  expect(countActive({ ...emptyFilters, sea: ['pacific', 'caribbean'] })).toBe(2);
  expect(toApiFilter({ ...emptyFilters, sea: ['pacific'] })).toEqual({ sea: ['pacific'] });
});

test('readSort falls back to newest', () => {
  expect(readSort(q('sort=bogus'))).toBe('newest');
  expect(readSort(q(''))).toBe('newest');
  expect(readSort(q('sort=top-rated'))).toBe('top-rated');
});

test('writeSort sets sort, drops page, drops newest', () => {
  expect(writeSort('most-comments', q('page=2&vegan=true')).toString()).toBe('vegan=true&sort=most-comments');
  expect(writeSort('newest', q('sort=most-rated')).toString()).toBe('');
});
