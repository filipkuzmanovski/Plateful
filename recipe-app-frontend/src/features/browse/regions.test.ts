import { REGIONS, SEAS_FALLBACK, seaLabel } from './regions';

test('mediterranean is no longer a region chip', () => {
  expect(REGIONS.map((r) => r.key)).not.toContain('mediterranean');
});

test('SEAS_FALLBACK lists the five seas in order', () => {
  expect(SEAS_FALLBACK).toEqual([
    { key: 'mediterranean', label: 'Mediterranean' },
    { key: 'caribbean', label: 'Caribbean' },
    { key: 'north_sea', label: 'North Sea' },
    { key: 'pacific', label: 'Sea of Japan & Pacific' },
    { key: 'gulf_of_mexico', label: 'Gulf of Mexico' },
  ]);
});

test('seaLabel resolves keys and humanizes unknown ones', () => {
  expect(seaLabel('pacific')).toBe('Sea of Japan & Pacific');
  expect(seaLabel('baltic_sea')).toBe('Baltic Sea');
});
