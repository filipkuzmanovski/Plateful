import type { RecipeDisplay } from './types';

/** True when a recipe is open to everyone: no review block (not the viewer's own) or approved and not hidden by reports. */
export const isLive = (recipe: Pick<RecipeDisplay, 'review'>): boolean =>
  !recipe.review || (recipe.review.status === 'approved' && !recipe.review.hidden);
