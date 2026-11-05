import { UtensilsCrossed } from 'lucide-react';
import type { RecipeDisplay } from '@/lib/types';

/**
 * The liked recipes as a grid of tick boxes, each a real checkbox inside its label. A ticked recipe shows its
 * chapter number. When the book is full the other boxes stay focusable but do nothing (aria-disabled).
 */
export function PickGrid({
  recipes,
  selected,
  full,
  onToggle,
}: {
  recipes: RecipeDisplay[];
  /** Recipe ids in chapter order. */
  selected: string[];
  full: boolean;
  onToggle: (id: string) => void;
}) {
  return (
    <ul className="grid grid-cols-2 gap-x-4 gap-y-6 lg:grid-cols-4">
      {recipes.map((recipe) => {
        const position = selected.indexOf(recipe.id);
        const ticked = position >= 0;
        const blocked = full && !ticked;
        return (
          <li key={recipe.id}>
            <label className={`group block ${blocked ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'}`}>
              <span className="relative block aspect-[4/3] overflow-hidden rounded-md bg-tint">
                {recipe.image ? (
                  <img src={recipe.image} alt="" loading="lazy" className="size-full object-cover" />
                ) : (
                  <span className="flex size-full items-center justify-center text-ink-soft">
                    <UtensilsCrossed aria-hidden="true" className="size-10" strokeWidth={1.25} />
                  </span>
                )}
                {ticked && (
                  <span
                    aria-hidden="true"
                    className="absolute top-3 right-3 flex size-8 items-center justify-center rounded-full bg-ink font-serif text-base font-medium text-on-ink tabular-nums"
                  >
                    {position + 1}
                  </span>
                )}
              </span>
              <span className="mt-2 flex items-start gap-2.5">
                <input
                  type="checkbox"
                  checked={ticked}
                  aria-disabled={blocked || undefined}
                  onChange={() => {
                    if (!blocked) onToggle(recipe.id);
                  }}
                  className="mt-0.5 size-5 shrink-0 cursor-[inherit] rounded-md accent-ink"
                />
                <span className="font-serif text-base leading-snug font-semibold break-words text-ink">
                  {ticked && <><span className="sr-only">Chapter {position + 1}:</span>{' '}</>}
                  {recipe.title}
                </span>
              </span>
            </label>
          </li>
        );
      })}
    </ul>
  );
}
