/** Sizes hint for a recipe card photo: a quarter of the container on desktop, half the viewport below. */
export const CARD_IMAGE_SIZES = '(min-width: 1024px) 270px, 50vw';
/** Sizes hint for the main photo on the recipe page. */
export const DETAIL_IMAGE_SIZES = '(min-width: 1024px) 460px, 100vw';

const SPOONACULAR_SIZED = /^(https?:\/\/img\.spoonacular\.com\/recipes\/[^/]+?)-\d+x\d+\.(\w+)$/;
const SPOONACULAR_WIDTHS = [
  [312, 231],
  [480, 360],
  [636, 393],
] as const;

/**
 * A `srcset` for Spoonacular photos, which are served in fixed sizes named `-{w}x{h}.{ext}`.
 * Any other URL (an upload, a photo without a size) returns undefined and keeps its plain `src`.
 */
export function recipeImageSrcSet(url: string | null | undefined): string | undefined {
  if (!url) return undefined;
  const match = SPOONACULAR_SIZED.exec(url);
  if (!match) return undefined;
  const [, base, ext] = match;
  return SPOONACULAR_WIDTHS.map(([w, h]) => `${base}-${w}x${h}.${ext} ${w}w`).join(', ');
}

/**
 * The largest Spoonacular size of a photo, for places that show it big or print it. Recipes store the small 312px
 * file. Any other URL comes back as it was.
 */
export function largestRecipeImage(url: string): string {
  const match = SPOONACULAR_SIZED.exec(url);
  if (!match) return url;
  const [, base, ext] = match;
  const [w, h] = SPOONACULAR_WIDTHS[SPOONACULAR_WIDTHS.length - 1];
  return `${base}-${w}x${h}.${ext}`;
}
