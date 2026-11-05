import type { ReactNode } from 'react';
import { Check, Clock, Flame, Leaf, Users, type LucideIcon } from 'lucide-react';
import { StarDisplay } from '@/components/StarDisplay';
import { Pill, SectionHeading } from '@/components/ui';
import type { RecipeDisplay } from '@/lib/types';
import { ingredientLine } from '@/lib/ingredientLine';
import { NutritionEstimate } from '@/features/nutrition/NutritionEstimate';
import { PhotoCollage } from './PhotoCollage';
import { RecipeCredit } from './RecipeCredit';
import { RecipeInstructions } from './RecipeInstructions';

/** One time/servings fact: a small icon, a value in the serif and a label. */
function Fact({ icon: Icon, value, label }: { icon: LucideIcon; value: ReactNode; label: string }) {
  return (
    <div className="flex min-w-0 flex-1 flex-col items-center gap-1 px-2 text-center">
      <Icon aria-hidden="true" className="size-4.5 text-ink-soft" strokeWidth={1.75} />
      {/* dt before dd for valid markup; `order-last` shows the label under the value. */}
      <dt className="order-last text-[13px] text-ink-soft">{label}</dt>
      <dd className="font-serif text-[22px] leading-tight font-medium text-ink tabular-nums">{value}</dd>
    </div>
  );
}

function DietBadges({ recipe }: { recipe: RecipeDisplay }) {
  const badges = [
    recipe.vegan && 'Vegan',
    recipe.vegetarian && 'Vegetarian',
    recipe.glutenFree && 'Gluten free',
    recipe.dairyFree && 'Dairy free',
  ].filter(Boolean) as string[];
  if (badges.length === 0) return null;
  return (
    <div className="flex flex-wrap gap-1.5">
      {badges.map((b) => (
        <Pill key={b} className="py-1">
          <Leaf aria-hidden="true" className="size-3" strokeWidth={2} />
          {b}
        </Pill>
      ))}
    </div>
  );
}

/**
 * The recipe itself: photos, facts, title, ingredients and instructions. The public page and the admin review page
 * both render it; each adds its own extras through the slots.
 */
export function RecipeBody({
  recipe,
  asideExtra,
  banner,
  actions,
  after,
}: {
  recipe: RecipeDisplay;
  /** Under the facts, in the photo column (the rating box). */
  asideExtra?: ReactNode;
  /** Above the cuisine tags (the author's review banner). */
  banner?: ReactNode;
  /** The row of buttons under the byline. */
  actions?: ReactNode;
  /** After the instructions (comments). */
  after?: ReactNode;
}) {
  const hasFacts = recipe.readyInMinutes !== null || recipe.cookingMinutes !== null || recipe.servings !== null;

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)] lg:gap-12">
      {/* Left: sticky photo and facts */}
      <aside>
        <div className="space-y-5 lg:sticky lg:top-24">
          <div className="overflow-hidden rounded-md">
            <PhotoCollage photos={recipe.photos} video={recipe.video ?? null} title={recipe.title} />
          </div>
          <div className="space-y-5">
            {hasFacts && (
              <dl className="flex divide-x divide-rule border-y border-rule py-4">
                {recipe.readyInMinutes !== null && (
                  <Fact icon={Clock} value={`${recipe.readyInMinutes} min`} label="total" />
                )}
                {recipe.cookingMinutes !== null && (
                  <Fact icon={Flame} value={`${recipe.cookingMinutes} min`} label="cook" />
                )}
                {recipe.servings !== null && <Fact icon={Users} value={recipe.servings} label="servings" />}
              </dl>
            )}
            <DietBadges recipe={recipe} />
            {recipe.calories === null && (
              <NutritionEstimate estimate={recipe.estimate} queryKey={['recipe', recipe.id]} />
            )}
            {recipe.calories !== null && (
              <div className="flex flex-wrap gap-1.5">
                <Pill className="py-1 tabular-nums">
                  <span className="font-semibold">{Math.round(recipe.calories)}</span> kcal
                </Pill>
                <Pill className="py-1 tabular-nums">{recipe.proteinGrams ?? '–'}g protein</Pill>
                <Pill className="py-1 tabular-nums">{recipe.fatGrams ?? '–'}g fat</Pill>
                <Pill className="py-1 tabular-nums">{recipe.carbsGrams ?? '–'}g carbs</Pill>
              </div>
            )}
            {asideExtra}
          </div>
        </div>
      </aside>

      {/* Right: content */}
      <article className="min-w-0">
        {banner}
        {recipe.cuisines.length > 0 && (
          <div className="mb-3 flex flex-wrap gap-1.5">
            {recipe.cuisines.map((c) => (
              <Pill key={c} tone="accent" className="capitalize">{c}</Pill>
            ))}
          </div>
        )}
        <h1 className="font-serif text-4xl leading-[1.05] font-medium tracking-[-0.015em] text-balance text-ink sm:text-5xl">
          {recipe.title}
        </h1>
        <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2">
          <StarDisplay average={recipe.averageRating} count={recipe.ratingCount} />
          <RecipeCredit recipe={recipe} />
        </div>
        {actions && <div className="mt-5 flex flex-wrap gap-2">{actions}</div>}

        <SectionHeading>Ingredients</SectionHeading>
        <ul className="rounded-card bg-tint p-2">
          {recipe.ingredients.map((ing, i) => {
            const line = ingredientLine(ing.ingredientName, ing.originalText);
            return (
              <li key={i}>
                <label className="group flex cursor-pointer items-start gap-3 rounded-lg px-3 py-2.5 transition-colors hover:bg-surface/60">
                  <span className="relative mt-0.5 flex size-5 shrink-0">
                    <input
                      type="checkbox"
                      className="peer size-5 cursor-pointer appearance-none rounded-md border border-field bg-surface transition checked:border-ink checked:bg-ink"
                    />
                    <Check
                      aria-hidden="true"
                      className="pointer-events-none absolute inset-0 m-auto size-3.5 text-on-ink opacity-0 transition-opacity peer-checked:opacity-100"
                      strokeWidth={3}
                    />
                  </span>
                  <span className="text-base leading-relaxed text-ink transition-colors group-has-checked:text-ink-soft group-has-checked:line-through group-has-checked:decoration-ink-soft">
                    {line.name && <><strong className="font-semibold">{line.name}</strong>, </>}
                    {line.text}
                  </span>
                </label>
              </li>
            );
          })}
        </ul>

        <SectionHeading>Instructions</SectionHeading>
        <RecipeInstructions recipe={recipe} />

        {after}
      </article>
    </div>
  );
}
