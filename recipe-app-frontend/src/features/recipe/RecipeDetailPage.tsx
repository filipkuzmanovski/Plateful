import { Link, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { getRecipe } from '@/api/recipes';
import { ApiError } from '@/api/client';
import { StarDisplay } from '@/components/StarDisplay';
import { SectionHeading, Spinner } from '@/components/ui';
import type { RecipeDisplay } from '@/lib/types';
import { RatingBox } from './RatingBox';
import { CommentsSection } from './CommentsSection';

function DietBadges({ recipe }: { recipe: RecipeDisplay }) {
  const badges = [
    recipe.vegan && 'Vegan',
    recipe.vegetarian && 'Vegetarian',
    recipe.glutenFree && 'Gluten free',
    recipe.dairyFree && 'Dairy free',
  ].filter(Boolean) as string[];
  if (badges.length === 0) return null;
  return (
    <div className="flex flex-wrap gap-1">
      {badges.map((b) => (
        <span key={b} className="border border-ink px-2 py-0.5 text-xs font-bold tracking-wide uppercase">{b}</span>
      ))}
    </div>
  );
}

export default function RecipeDetailPage() {
  const { id } = useParams<{ id: string }>();
  const query = useQuery({ queryKey: ['recipe', id], queryFn: () => getRecipe(id!), retry: false });

  if (query.isPending) return <Spinner />;
  if (query.isError) {
    const notFound = query.error instanceof ApiError && query.error.status === 404;
    return (
      <p className="py-12 text-center text-muted">
        {notFound ? 'Recipe not found.' : "Couldn't load this recipe."}
      </p>
    );
  }
  const recipe = query.data;

  return (
    <div className="flex flex-col gap-8 lg:flex-row">
      {/* Left: sticky facts card */}
      <aside className="lg:w-2/5">
        <div className="border border-hairline lg:sticky lg:top-4">
          {recipe.image ? (
            <img src={recipe.image} alt={recipe.title} className="w-full object-cover" />
          ) : (
            <div className="flex h-52 items-center justify-center bg-hairline text-5xl">🍽</div>
          )}
          <div className="space-y-3 p-4 text-sm">
            <div className="flex flex-wrap gap-x-4 gap-y-1 text-muted">
              {recipe.readyInMinutes !== null && <span>⏱ {recipe.readyInMinutes} min total</span>}
              {recipe.cookingMinutes !== null && <span>cook {recipe.cookingMinutes} min</span>}
              {recipe.servings !== null && <span>🍽 {recipe.servings} servings</span>}
            </div>
            <DietBadges recipe={recipe} />
            {recipe.calories !== null && (
              <p className="text-muted">
                {Math.round(recipe.calories)} kcal · {recipe.proteinGrams ?? '–'}g protein ·{' '}
                {recipe.fatGrams ?? '–'}g fat · {recipe.carbsGrams ?? '–'}g carbs
              </p>
            )}
            <div className="border-t border-hairline pt-3">
              <RatingBox recipeId={recipe.id} />
            </div>
          </div>
        </div>
      </aside>

      {/* Right: content */}
      <article className="flex-1">
        <h1 className="text-3xl font-extrabold tracking-tight">{recipe.title}</h1>
        <div className="mt-1 flex items-center gap-3">
          <StarDisplay average={recipe.averageRating} count={recipe.ratingCount} />
          {recipe.user ? (
            <Link to={`/users/${recipe.user.id}`} className="text-sm text-muted underline">
              by {recipe.user.firstName} {recipe.user.lastName}
            </Link>
          ) : recipe.sourceName ? (
            <a href={recipe.sourceUrl ?? '#'} target="_blank" rel="noreferrer" className="text-sm text-muted underline">
              From {recipe.sourceName} ↗
            </a>
          ) : null}
        </div>

        <SectionHeading>Ingredients</SectionHeading>
        <ul className="space-y-1">
          {recipe.ingredients.map((ing, i) => (
            <li key={i}>
              <label className="flex cursor-pointer items-baseline gap-2 text-sm">
                <input type="checkbox" className="accent-ink" />
                <span>{ing.originalText}</span>
              </label>
            </li>
          ))}
        </ul>

        <SectionHeading>Instructions</SectionHeading>
        {recipe.instructionsSteps.length > 0 ? (
          <ol className="space-y-3">
            {recipe.instructionsSteps.map((step) => (
              <li key={step.number} className="flex gap-3 text-sm">
                <span className="font-extrabold">{step.number}.</span>
                <span>{step.step}</span>
              </li>
            ))}
          </ol>
        ) : recipe.instructions ? (
          <p className="text-sm whitespace-pre-line">{recipe.instructions}</p>
        ) : (
          <p className="text-sm text-muted">No instructions provided.</p>
        )}

        <SectionHeading>Comments ({recipe.commentCount})</SectionHeading>
        <section aria-label="comments">
          <CommentsSection recipeId={recipe.id} recipeOwnerId={recipe.user?.id ?? null} />
        </section>
      </article>
    </div>
  );
}
