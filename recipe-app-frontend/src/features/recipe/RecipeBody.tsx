import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { Check, Clock, ExternalLink, Flame, Leaf, Users, type LucideIcon } from 'lucide-react';
import { StarDisplay } from '@/components/StarDisplay';
import { Pill, SectionHeading } from '@/components/ui';
import type { RecipeDisplay } from '@/lib/types';
import { PhotoCollage } from './PhotoCollage';

/** One time/servings fact in the photo card: a terracotta icon, a value and a small label. */
function Fact({ icon: Icon, value, label }: { icon: LucideIcon; value: ReactNode; label: string }) {
  return (
    <div className="flex min-w-0 flex-1 flex-col items-center gap-1 rounded-2xl bg-cream px-2 py-3 text-center">
      <Icon aria-hidden="true" className="size-4.5 text-terracotta" strokeWidth={1.75} />
      {/* dt before dd for valid markup; `order-last` shows the label under the value. */}
      <dt className="order-last text-xs text-olive/70">{label}</dt>
      <dd className="font-serif text-lg leading-tight font-semibold text-olive tabular-nums">{value}</dd>
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
        <Pill key={b} tone="olive" className="py-1">
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
  /** Under the facts, inside the photo card (the rating box). */
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
      {/* Left: sticky photo and facts card */}
      <aside>
        <div className="overflow-hidden rounded-card bg-paper shadow-card ring-1 ring-sand/70 lg:sticky lg:top-24">
          <PhotoCollage photos={recipe.photos} title={recipe.title} />
          <div className="space-y-5 p-5 sm:p-6">
            {hasFacts && (
              <dl className="flex gap-2">
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
              <Pill key={c} tone="saffron" className="capitalize">{c}</Pill>
            ))}
          </div>
        )}
        <h1 className="font-serif text-4xl leading-[1.05] font-semibold tracking-tight text-balance text-olive sm:text-5xl">
          {recipe.title}
        </h1>
        <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2">
          <StarDisplay average={recipe.averageRating} count={recipe.ratingCount} />
          {recipe.user ? (
            <Link
              to={`/users/${recipe.user.id}`}
              className="text-sm text-olive underline decoration-olive/30 underline-offset-4 transition hover:decoration-saffron"
            >
              by {recipe.user.firstName} {recipe.user.lastName}
            </Link>
          ) : recipe.sourceName ? (
            recipe.sourceUrl ? (
              <a
                href={recipe.sourceUrl}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1 text-sm text-olive underline decoration-olive/30 underline-offset-4 transition hover:decoration-saffron"
              >
                From {recipe.sourceName}
                <ExternalLink aria-hidden="true" className="size-3.5 text-olive/60" />
              </a>
            ) : (
              <span className="text-sm text-olive/70">From {recipe.sourceName}</span>
            )
          ) : null}
        </div>
        {actions && <div className="mt-5 flex flex-wrap gap-2">{actions}</div>}

        <SectionHeading>Ingredients</SectionHeading>
        <ul className="rounded-card bg-paper p-2 shadow-card ring-1 ring-sand/70">
          {recipe.ingredients.map((ing, i) => (
            <li key={i}>
              <label className="group flex cursor-pointer items-start gap-3 rounded-xl px-3 py-2.5 transition-colors hover:bg-cream">
                <span className="relative mt-0.5 flex size-5 shrink-0">
                  <input
                    type="checkbox"
                    className="peer size-5 cursor-pointer appearance-none rounded-md border border-olive/30 bg-paper transition checked:border-olive checked:bg-olive"
                  />
                  <Check
                    aria-hidden="true"
                    className="pointer-events-none absolute inset-0 m-auto size-3.5 text-cream opacity-0 transition-opacity peer-checked:opacity-100"
                    strokeWidth={3}
                  />
                </span>
                <span className="text-[15px] leading-relaxed text-olive transition-colors group-has-checked:text-olive/70 group-has-checked:line-through group-has-checked:decoration-olive/40">
                  {ing.originalText}
                </span>
              </label>
            </li>
          ))}
        </ul>

        <SectionHeading>Instructions</SectionHeading>
        {recipe.instructionsSteps.length > 0 ? (
          <ol className="space-y-6">
            {recipe.instructionsSteps.map((step) => (
              <li key={step.number} className="flex gap-4 sm:gap-5">
                <span className="w-8 shrink-0 text-right font-serif text-3xl leading-none font-medium text-terracotta italic tabular-nums">
                  {step.number}
                </span>
                <span className="pt-1 text-[15px] leading-relaxed text-olive">{step.step}</span>
              </li>
            ))}
          </ol>
        ) : recipe.instructions ? (
          <p className="text-[15px] leading-relaxed whitespace-pre-line text-olive">
            {recipe.instructions.replace(/<[^>]+>/g, ' ').replace(/\s+\n/g, '\n').trim()}
          </p>
        ) : (
          <p className="text-sm text-olive/70 italic">No instructions provided.</p>
        )}

        {after}
      </article>
    </div>
  );
}
