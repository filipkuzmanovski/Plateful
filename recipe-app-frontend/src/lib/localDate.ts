const pad = (n: number) => String(n).padStart(2, '0');

/** The local calendar date as `YYYY-MM-DD` (never the UTC date, which can be a day off near midnight). */
export function localIsoDate(d: Date = new Date()): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** "2026-10-28" -> "28 October". The ISO date is read as a calendar date, so no timezone can shift it. */
export function formatMealDate(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-GB', { day: 'numeric', month: 'long' });
}
