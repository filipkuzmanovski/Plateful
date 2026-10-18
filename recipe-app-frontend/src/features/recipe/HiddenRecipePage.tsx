import { Link } from 'react-router';
import type { HiddenState } from '@/lib/types';
import { SaveRecipeButton } from '@/features/saved/SaveRecipeButton';

const COPY: Record<HiddenState, string> = {
  under_review: 'This recipe is being reviewed after an edit. Check back soon.',
  unavailable: "This recipe isn't available right now.",
};

/** What a reader sees for a recipe that was approved once but is hidden now. Saving stays available so it can be unsaved. */
export function HiddenRecipePage({
  recipeId,
  approvedTitle,
  state,
}: {
  recipeId: string;
  approvedTitle: string;
  state: HiddenState;
}) {
  return (
    <div className="mx-auto flex max-w-xl flex-col items-center gap-4 px-4 py-16 text-center">
      <h1 className="font-serif text-4xl leading-[1.05] font-semibold tracking-tight text-balance text-olive sm:text-5xl">
        {approvedTitle}
      </h1>
      <p className="text-[15px] leading-relaxed text-olive/70">{COPY[state]}</p>
      <SaveRecipeButton recipeId={recipeId} />
      <Link to="/" className="text-sm font-semibold text-olive underline underline-offset-4 hover:decoration-saffron">
        Back to all recipes
      </Link>
    </div>
  );
}
