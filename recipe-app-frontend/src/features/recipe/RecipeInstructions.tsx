import type { RecipeDisplay } from '@/lib/types';

/** A recipe's method: numbered steps, or its plain text when it has no steps. */
export function RecipeInstructions({ recipe }: { recipe: RecipeDisplay }) {
  if (recipe.instructionsSteps.length > 0) {
    return (
      <ol className="space-y-6">
        {recipe.instructionsSteps.map((step) => (
          <li key={step.number} className="flex gap-4 sm:gap-5">
            <span className="w-10 shrink-0 font-serif text-4xl leading-none font-medium text-accent-strong tabular-nums">
              {step.number}
            </span>
            <span className="max-w-prose pt-1 text-base leading-relaxed text-ink">{step.step}</span>
          </li>
        ))}
      </ol>
    );
  }
  if (recipe.instructions) {
    return (
      <p className="max-w-prose text-base leading-relaxed whitespace-pre-line text-ink">
        {recipe.instructions.replace(/<[^>]+>/g, ' ').replace(/\s+\n/g, '\n').trim()}
      </p>
    );
  }
  return <p className="text-[15px] text-ink-soft italic">No instructions provided.</p>;
}
