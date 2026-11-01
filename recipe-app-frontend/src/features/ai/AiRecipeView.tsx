import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ChefHat, Clock, Flame, Leaf, Sparkles, Users, type LucideIcon } from 'lucide-react';
import { cuisinesQueryKey, getCuisines } from '@/api/cuisines';
import { Pill, SectionHeading } from '@/components/ui';
import { humanizeKey } from '@/features/browse/regions';
import type { GeneratedRecipe } from '@/lib/types';
import { AI_NOTE, providerLine } from './aiMessages';

function Fact({ icon: Icon, value, label }: { icon: LucideIcon; value: ReactNode; label: string }) {
  return (
    <div className="flex min-w-0 flex-1 flex-col items-center gap-1 rounded-2xl bg-cream px-2 py-3 text-center">
      <Icon aria-hidden="true" className="size-4.5 text-terracotta" strokeWidth={1.75} />
      <dt className="order-last text-xs text-olive/70">{label}</dt>
      <dd className="font-serif text-lg leading-tight font-semibold text-olive tabular-nums">{value}</dd>
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

  const ingredientItem = (i: { name: string; amount: string }, idx: number) => (
    <li key={idx} className="rounded-xl px-3 py-2.5 text-[15px] leading-relaxed text-olive">
      <strong className="font-semibold">{i.name}</strong>
      {i.amount && <>, {i.amount}</>}
    </li>
  );

  return (
    <div className="space-y-6">
      <p
        role="note"
        className="flex items-start gap-3 rounded-2xl border border-saffron/40 bg-saffron/15 px-4 py-3 text-sm text-olive"
      >
        <Sparkles aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-saffron-deep" strokeWidth={2} />
        <span>{AI_NOTE}</span>
      </p>

      <div className="grid gap-8 lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)] lg:gap-12">
        <aside>
          <div className="overflow-hidden rounded-card bg-paper shadow-card ring-1 ring-sand/70 lg:sticky lg:top-24">
            <div aria-hidden="true" className="flex h-40 items-center justify-center bg-sand/70">
              <ChefHat className="size-16 text-olive/60" strokeWidth={1.25} />
            </div>
            <div className="space-y-5 p-5 sm:p-6">
              <dl className="flex gap-2">
                <Fact icon={Clock} value={`${recipe.prepMinutes} min`} label="prep" />
                <Fact icon={Flame} value={`${recipe.cookMinutes} min`} label="cook" />
                <Fact icon={Users} value={recipe.servings} label="servings" />
              </dl>
              <p className="text-sm text-olive">
                {recipe.allergens.length > 0
                  ? `Contains: ${recipe.allergens.map((a) => a.label).join(', ')}`
                  : 'No listed allergens found'}
              </p>
              {diets.length > 0 && (
                <div className="flex flex-wrap gap-1.5">
                  {diets.map((d) => (
                    <Pill key={d} tone="olive" className="py-1">
                      <Leaf aria-hidden="true" className="size-3" strokeWidth={2} />
                      {d}
                    </Pill>
                  ))}
                </div>
              )}
            </div>
          </div>
        </aside>

        <article className="min-w-0">
          {cuisineLabel && (
            <div className="mb-3 flex flex-wrap gap-1.5">
              <Pill tone="saffron">{cuisineLabel}</Pill>
            </div>
          )}
          <h1 className="font-serif text-4xl leading-[1.05] font-semibold tracking-tight text-balance text-olive sm:text-5xl">
            {recipe.title}
          </h1>
          {recipe.description && <p className="mt-4 text-[15px] leading-relaxed text-olive/85">{recipe.description}</p>}
          {actions && <div className="mt-5 flex flex-wrap gap-2">{actions}</div>}

          <SectionHeading>Ingredients</SectionHeading>
          <ul className="rounded-card bg-paper p-2 shadow-card ring-1 ring-sand/70">{main.map(ingredientItem)}</ul>
          {extras.length > 0 && (
            <>
              <h3 className="mt-6 mb-3 font-serif text-lg font-semibold text-olive">You&apos;ll also need</h3>
              <ul className="rounded-card bg-paper p-2 shadow-card ring-1 ring-sand/70">{extras.map(ingredientItem)}</ul>
            </>
          )}

          <SectionHeading>Instructions</SectionHeading>
          <ol className="space-y-6">
            {recipe.steps.map((step, i) => (
              <li key={i} className="flex gap-4 sm:gap-5">
                <span className="w-8 shrink-0 text-right font-serif text-3xl leading-none font-medium text-terracotta italic tabular-nums">
                  {i + 1}
                </span>
                <span className="pt-1 text-[15px] leading-relaxed text-olive">{step}</span>
              </li>
            ))}
          </ol>

          {made && <p className="mt-10 text-xs text-olive/70">{made}</p>}
        </article>
      </div>
    </div>
  );
}
