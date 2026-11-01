import { formatMealDate, localIsoDate } from './localDate';

describe('localIsoDate', () => {
  it('keeps the local day at 23:30 local time', () => {
    expect(localIsoDate(new Date(2026, 9, 31, 23, 30))).toBe('2026-10-31');
  });

  it('keeps the local day just after midnight', () => {
    expect(localIsoDate(new Date(2026, 10, 1, 0, 5))).toBe('2026-11-01');
  });

  it('zero-pads month and day', () => {
    expect(localIsoDate(new Date(2026, 0, 5, 12))).toBe('2026-01-05');
  });

  it('defaults to now', () => {
    expect(localIsoDate()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});

describe('formatMealDate', () => {
  it('formats in English as "d MMMM"', () => {
    expect(formatMealDate('2026-10-28')).toBe('28 October');
    expect(formatMealDate('2026-01-05')).toBe('5 January');
  });
});
