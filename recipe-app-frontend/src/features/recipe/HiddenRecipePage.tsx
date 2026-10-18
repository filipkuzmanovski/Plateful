import { Link } from 'react-router';
import type { HiddenState } from '@/lib/types';
import { SaveRecipeButton } from '@/features/saved/SaveRecipeButton';
import { useSaveControl } from '@/features/saved/useSaveControl';

const COPY: Record<HiddenState, string> = {
  under_review: 'This recipe is being reviewed after an edit. Check back soon.',
  unavailable: "This recipe isn't available right now.",
};

/**
 * What a reader sees for a recipe that was approved once but is hidden now. The button shows only for a
 * recipe the reader already saved, so it can be unsaved; the server refuses a new save of a hidden recipe.
 */
export function HiddenRecipePage({
  recipeId,
  approvedTitle,
  state,
}: {
  recipeId: string;
  approvedTitle: string;
  state: HiddenState;
}) {
  const { saved } = useSaveControl(recipeId);
  return (
    <div className="mx-auto flex max-w-xl flex-col items-center gap-4 px-4 py-16 text-center">
      <h1 className="font-serif text-4xl leading-[1.05] font-semibold tracking-tight text-balance text-olive sm:text-5xl">
        {approvedTitle}
      </h1>
      <p className="text-[15px] leading-relaxed text-olive/70">{COPY[state]}</p>
      {saved && <SaveRecipeButton recipeId={recipeId} />}
      <Link to="/" className="text-sm font-semibold text-olive underline underline-offset-4 hover:decoration-saffron">
        Back to all recipes
      </Link>
    </div>
  );
}
