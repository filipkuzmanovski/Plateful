import { recipeFixture } from '@/test/fixtures';
import type { SavedItem } from '@/lib/types';
import { bookFacts, formatMinutes, leafFromParam } from './bookFacts';

const chapter = (cuisines: string[], readyInMinutes: number | null): SavedItem => ({
  kind: 'recipe', recipe: recipeFixture({ cuisines, readyInMinutes }), stub: null,
});
const stub: SavedItem = { kind: 'stub', recipe: null, stub: { id: 's', approvedTitle: 'Old', state: 'unavailable' } };
const label = (key: string) => key.toUpperCase();

describe('bookFacts', () => {
  test('givesDistinctLabelsInChapterOrder', () => {
    const facts = bookFacts([chapter(['thai', 'asian'], 10), chapter(['asian', 'greek'], 20)], label);
    expect(facts.cuisines).toEqual(['THAI', 'ASIAN', 'GREEK']);
  });

  test('addsTheTimesAndSkipsNulls', () => {
    expect(bookFacts([chapter([], 10), chapter([], null), chapter([], 25)], label).totalMinutes).toBe(35);
  });

  test('ignoresStubs', () => {
    expect(bookFacts([stub], label)).toEqual({ cuisines: [], totalMinutes: 0 });
  });
});

describe('formatMinutes', () => {
  test.each([
    [45, '45 min'],
    [60, '1 h'],
    [95, '1 h 35 min'],
    [120, '2 h'],
  ])('%i minutes reads %s', (minutes, text) => {
    expect(formatMinutes(minutes)).toBe(text);
  });
});

describe('leafFromParam', () => {
  test.each([[null], ['abc'], ['0'], ['-1'], ['99'], ['1.5'], [''], ['2x'], ['1e1']])('%s gives the cover', (raw) => {
    expect(leafFromParam(raw, 7)).toBe(1);
  });

  test('aValidPageIsUsed', () => {
    expect(leafFromParam('3', 7)).toBe(3);
    expect(leafFromParam('7', 7)).toBe(7);
  });

  test('aPageBeyondAShortBookGivesTheCover', () => {
    expect(leafFromParam('3', 1)).toBe(1);
  });
});
