import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ChefHat, Clock, Flame, Leaf, Sparkles, Users, type LucideIcon } from 'lucide-react';
import { aiRecipeQueryKey } from '@/api/ai';
import { cuisinesQueryKey, getCuisines } from '@/api/cuisines';
import { Pill, SectionHeading } from '@/components/ui';
import { NutritionEstimate } from '@/features/nutrition/NutritionEstimate';
import { humanizeKey } from '@/features/browse/regions';
import type { GeneratedRecipe } from '@/lib/types';
import { AI_NOTE, providerLine } from './aiMessages';

function Fact({ icon: Icon, value, label }: { icon: LucideIcon; value: ReactNode; label: string }) {
  return (
    <div className="flex min-w-0 flex-1 flex-col items-center gap-1 px-2 text-center">
      <Icon aria-hidden="true" className="size-4.5 text-ink-soft" strokeWidth={1.75} />
      <dt className="order-last text-[13px] text-ink-soft">{label}</dt>
      <dd className="font-serif text-[22px] leading-tight font-medium text-ink tabular-nums">{value}</dd>
    </div>
  );
}

/**
 * An AI-generated recipe in full: the AI note, a plain header in place of a photo, facts, allergens and
 * diets, ingredients, and numbered steps. No ratings, comments or share. `actions` is a row of buttons
 * under the title (Save, Open, ...).
 */
export function AiRecipeView({ recipe, actions }: { recipe: GeneratedRecipe; actions?: ReactNode }) {
  const cuisines = useQuery({ queryKey: cuisinesQueryKey, queryFn: getCuisines, staleTime: Infinity });
  const cuisineLabel = recipe.cuisine
    ? (cuisines.data?.find((c) => c.key === recipe.cuisine)?.label ?? humanizeKey(recipe.cuisine))
    : null;

  const diets = [
    recipe.diets.vegetarian && 'Vegetarian',
    recipe.diets.vegan && 'Vegan',
    recipe.diets.glutenFree && 'Gluten-free',
    recipe.diets.dairyFree && 'Dairy-free',
  ].filter(Boolean) as string[];

  const main = recipe.ingredients.filter((i) => !i.extra);
  const extras = recipe.ingredients.filter((i) => i.extra);
  const made = providerLine(recipe.provider);
  const swapLine = recipe.swaps.length > 0
    ? `Swapped for your food preferences: ${recipe.swaps.map((s) => `${s.from} → ${s.to}`).join(', ')}.`
    : null;

  const ingredientItem = (i: { name: string; amount: string }, idx: number) => (
    <li key={idx} className="rounded-lg px-3 py-2.5 text-base leading-relaxed text-ink">
      <strong className="font-semibold">{i.name}</strong>
      {i.amount && <>, {i.amount}</>}
    </li>
  );

  return (
    <div className="space-y-6">
      <p
        role="note"
        className="flex items-start gap-3 rounded-card bg-accent/15 px-4 py-3 text-[15px] text-ink"
      >
        <Sparkles aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-accent-ink" strokeWidth={2} />
        <span>{AI_NOTE}</span>
      </p>

      <div className="grid gap-8 lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)] lg:gap-12">
        <aside>
          <div className="space-y-5 lg:sticky lg:top-24">
            <div aria-hidden="true" className="flex h-40 items-center justify-center rounded-md bg-tint">
              <ChefHat className="size-16 text-ink-soft" strokeWidth={1.25} />
            </div>
            <div className="space-y-5">
              <dl className="flex divide-x divide-rule border-y border-rule py-4">
                <Fact icon={Clock} value={`${recipe.prepMinutes} min`} label="prep" />
                <Fact icon={Flame} value={`${recipe.cookMinutes} min`} label="cook" />
                <Fact icon={Users} value={recipe.servings} label="servings" />
              </dl>
              <p className="text-[15px] text-ink">
                {recipe.allergens.length > 0
                  ? `Contains: ${recipe.allergens.map((a) => a.label).join(', ')}`
                  : 'No listed allergens found'}
              </p>
              {diets.length > 0 && (
                <div className="flex flex-wrap gap-1.5">
                  {diets.map((d) => (
                    <Pill key={d} className="py-1">
                      <Leaf aria-hidden="true" className="size-3" strokeWidth={2} />
                      {d}
                    </Pill>
                  ))}
                </div>
              )}
              <NutritionEstimate estimate={recipe.estimate} queryKey={aiRecipeQueryKey(recipe.id)} />
            </div>
          </div>
        </aside>

        <article className="min-w-0">
          {cuisineLabel && (
            <div className="mb-3 flex flex-wrap gap-1.5">
              <Pill tone="accent">{cuisineLabel}</Pill>
            </div>
          )}
          <h1 className="font-serif text-4xl leading-[1.05] font-medium tracking-[-0.015em] text-balance text-ink sm:text-5xl">
            {recipe.title}
          </h1>
          {recipe.kind === 'post' && (
            <div className="mt-3 space-y-1 text-[15px] text-ink-soft">
              <p>Based on an Instagram post</p>
              {recipe.origin === 'dish' && <p>The post had no recipe, so this is the AI&apos;s own take on the dish.</p>}
              {swapLine && <p>{swapLine}</p>}
            </div>
          )}
          {recipe.description && <p className="mt-4 max-w-prose text-base leading-relaxed text-ink">{recipe.description}</p>}
          {actions && <div className="mt-5 flex flex-wrap gap-2">{actions}</div>}

          <SectionHeading>Ingredients</SectionHeading>
          <ul className="rounded-card bg-tint p-2">{main.map(ingredientItem)}</ul>
          {extras.length > 0 && (
            <>
              <h3 className="mt-6 mb-3 font-serif text-lg font-semibold text-ink">You&apos;ll also need</h3>
              <ul className="rounded-card bg-tint p-2">{extras.map(ingredientItem)}</ul>
            </>
          )}

          <SectionHeading>Instructions</SectionHeading>
          <ol className="space-y-6">
            {recipe.steps.map((step, i) => (
              <li key={i} className="flex gap-4 sm:gap-5">
                <span className="w-10 shrink-0 font-serif text-4xl leading-none font-medium text-accent-strong tabular-nums">
                  {i + 1}
                </span>
                <span className="max-w-prose pt-1 text-base leading-relaxed text-ink">{step}</span>
              </li>
            ))}
          </ol>

          {made && <p className="mt-10 text-[13px] text-ink-soft">{made}</p>}
        </article>
      </div>
    </div>
  );
}
