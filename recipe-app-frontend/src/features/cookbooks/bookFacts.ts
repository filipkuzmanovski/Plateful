import type { SavedItem } from '@/lib/types';

/** What the book's opening says about its visible chapters. No AI is involved. */
export function bookFacts(
  chapters: SavedItem[],
  labelOf: (key: string) => string,
): { cuisines: string[]; totalMinutes: number } {
  const cuisines = new Set<string>();
  let totalMinutes = 0;
  for (const chapter of chapters) {
    if (!chapter.recipe) continue;
    chapter.recipe.cuisines.forEach((key) => cuisines.add(labelOf(key)));
    totalMinutes += chapter.recipe.readyInMinutes ?? 0;
  }
  return { cuisines: [...cuisines], totalMinutes };
}

/** 45 reads "45 min", 95 reads "1 h 35 min", 120 reads "2 h". */
export function formatMinutes(minutes: number): string {
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) return `${rest} min`;
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
}

/** The current leaf from the URL, 1-based. Anything that is not a page of this book means the cover. */
export function leafFromParam(raw: string | null, leafCount: number): number {
  if (raw === null || !/^\d+$/.test(raw)) return 1;
  const page = Number(raw);
  return page >= 1 && page <= leafCount ? page : 1;
}
