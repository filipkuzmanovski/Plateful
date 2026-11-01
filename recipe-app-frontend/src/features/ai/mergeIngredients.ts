import type { ScanResult } from '@/lib/types';

const MAX_NAME_LENGTH = 60;

const clean = (raw: string) => raw.trim().replace(/\s+/g, ' ').slice(0, MAX_NAME_LENGTH).trim();

/**
 * Adds the found names after the current ones, skipping blanks and case-insensitive duplicates, until
 * `max` is reached. Current chips are never dropped or reordered. `didntFit` holds the new names that
 * did not fit, in found order (a name already in the chips is not "left over").
 */
export function mergeIngredients(
  current: string[],
  found: string[],
  max: number,
): { merged: string[]; didntFit: string[] } {
  const merged = [...current];
  const seen = new Set(current.map((c) => c.toLowerCase()));
  const didntFit: string[] = [];
  for (const raw of found) {
    const name = clean(raw);
    const key = name.toLowerCase();
    if (name === '' || seen.has(key)) continue;
    seen.add(key);
    if (merged.length < max) merged.push(name);
    else didntFit.push(name);
  }
  return { merged, didntFit };
}

/** "allergy: Peanuts" -> "Peanuts", "diet: Vegan" -> "Vegan", "dislike" -> "you dislike it". */
function reasonLabel(reason: string): string {
  if (reason === 'dislike' || reason.startsWith('dislike: ')) return 'you dislike it';
  for (const prefix of ['allergy: ', 'diet: ']) {
    if (reason.startsWith(prefix)) return reason.slice(prefix.length);
  }
  return reason;
}

/** One ingredient may arrive once per reason; they are grouped under it, in first-seen order. */
export function leftOutLine(leftOut: ScanResult['leftOut']): string | null {
  if (leftOut.length === 0) return null;
  const groups = new Map<string, string[]>();
  for (const { ingredient, reason } of leftOut) {
    const labels = groups.get(ingredient) ?? [];
    const label = reasonLabel(reason);
    if (!labels.includes(label)) labels.push(label);
    groups.set(ingredient, labels);
  }
  const parts = [...groups].map(([name, labels]) => `${name} (${labels.join(', ')})`);
  return `Left out because of your food preferences: ${parts.join(', ')}.`;
}
