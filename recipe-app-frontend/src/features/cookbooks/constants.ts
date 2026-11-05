export const MIN_RECIPES = 5;
export const MAX_RECIPES = 10;
export const MAX_COOKBOOKS = 20;
export const MAX_SUBTITLE = 80;
export const DEFAULT_SUBTITLE = 'Recipes I loved';

// Literal class names, so Tailwind sees them when it scans the source.
const COVERS = [
  'bg-cover-1 text-on-cover',
  'bg-cover-2 text-on-cover',
  'bg-cover-3 text-on-cover',
  'bg-cover-4 text-on-cover',
];

/** A book's cover colour, cycling by volume so a shelf looks varied. */
export function coverClasses(volume: number): string {
  return COVERS[(volume - 1) % COVERS.length];
}
