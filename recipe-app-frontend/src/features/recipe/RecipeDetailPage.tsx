import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  Check,
  CircleAlert,
  Clock,
  ExternalLink,
  Flame,
  Leaf,
  Pencil,
  SearchX,
  Trash2,
  Users,
  type LucideIcon,
} from 'lucide-react';
import { deleteRecipe, getRecipe } from '@/api/recipes';
import { ApiError } from '@/api/client';
import { deletePhotos, pathFromPublicUrl } from '@/api/photos';
import { useAuth } from '@/auth/AuthProvider';
import { StarDisplay } from '@/components/StarDisplay';
import { Button, EmptyState, Pill, SectionHeading, Spinner } from '@/components/ui';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import type { RecipeDisplay } from '@/lib/types';
import { RatingBox } from './RatingBox';
import { CommentsSection } from './CommentsSection';
import { PhotoCollage } from './PhotoCollage';
import { SaveRecipeButton } from '@/features/saved/SaveRecipeButton';
import { HiddenRecipePage } from './HiddenRecipePage';
import { ReviewStatusBanner } from '@/features/review/ReviewStatusBanner';

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

export default function RecipeDetailPage() {
  const { id } = useParams<{ id: string }>();
  const query = useQuery({ queryKey: ['recipe', id], queryFn: () => getRecipe(id!), retry: false });
  const { profile, refreshProfile } = useAuth();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const removeRecipe = useMutation({
    mutationFn: (recipeId: string) => deleteRecipe(recipeId),
    onSuccess: (_data, recipeId) => {
      // The row is gone, so its files are orphans; the helper never throws.
      const paths = (query.data?.photos ?? []).map(pathFromPublicUrl).filter((p): p is string => p !== null);
      if (paths.length > 0) void deletePhotos(paths);
      navigate('/'); // leave first, so the removed query isn't refetched by this page
      queryClient.removeQueries({ queryKey: ['recipe', recipeId] });
      void queryClient.invalidateQueries({ queryKey: ['recipes'] });
      void queryClient.invalidateQueries({ queryKey: ['userRecipes'] });
      // A saved copy of the recipe is gone too (ON DELETE CASCADE): drop it from the Saved tab and the id set.
      void queryClient.invalidateQueries({ queryKey: ['savedRecipes'] });
      void queryClient.invalidateQueries({ queryKey: ['savedIds'] });
      void refreshProfile(); // deleting a rejected recipe lowers the needs-attention count
    },
    onError: (e) => toast.error(e instanceof ApiError ? e.detail : "Couldn't delete the recipe"),
  });

  if (query.isPending) return <Spinner />;
  if (query.isError) {
    const error = query.error;
    const notFound = error instanceof ApiError && error.status === 404;
    // A recipe approved once but hidden now answers 404 with a code and its last approved title.
    if (notFound && (error.code === 'under_review' || error.code === 'unavailable') && error.approvedTitle) {
      return <HiddenRecipePage recipeId={id!} approvedTitle={error.approvedTitle} state={error.code} />;
    }
    return (
      <EmptyState
        icon={notFound ? SearchX : CircleAlert}
        action={
          <Link to="/" className="text-sm font-semibold text-olive underline underline-offset-4 hover:decoration-saffron">
            Back to all recipes
          </Link>
        }
      >
        {notFound ? 'Recipe not found.' : "Couldn't load this recipe."}
      </EmptyState>
    );
  }
  const recipe = query.data;
  const isOwner = !!profile && recipe.user?.id === profile.id;
  // Only an approved recipe is open to feedback; the API refuses writes on the others (409 recipe_not_approved).
  const acceptsFeedback = !recipe.review || recipe.review.status === 'approved';
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
            {acceptsFeedback && (
              <div className="border-t border-sand pt-5">
                <RatingBox recipeId={recipe.id} />
              </div>
            )}
          </div>
        </div>
      </aside>

      {/* Right: content */}
      <article className="min-w-0">
        {isOwner && recipe.review && <ReviewStatusBanner review={recipe.review} recipeId={recipe.id} />}
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
        <div className="mt-5 flex flex-wrap gap-2">
          <SaveRecipeButton recipeId={recipe.id} />
          {isOwner && (
            <>
              <Link
                to={`/recipes/${recipe.id}/edit`}
                className="inline-flex items-center gap-2 rounded-full border border-olive/20 bg-paper px-4 py-2 text-sm font-semibold text-olive transition hover:border-olive/50 hover:bg-cream"
              >
                <Pencil aria-hidden="true" className="size-3.5" />
                Edit
              </Link>
              <Button variant="danger" className="!px-4 !py-2" onClick={() => setConfirmDelete(true)}>
                <Trash2 aria-hidden="true" className="size-3.5" />
                Delete recipe
              </Button>
              <ConfirmDialog
                open={confirmDelete}
                title="Delete recipe?"
                description="The recipe, its ratings and its comments will be permanently removed."
                confirmLabel="Confirm"
                busy={removeRecipe.isPending}
                onConfirm={() => { if (!removeRecipe.isPending) removeRecipe.mutate(recipe.id); }}
                onCancel={() => setConfirmDelete(false)}
              />
            </>
          )}
        </div>

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

        {acceptsFeedback && (
          <>
            <SectionHeading>
              Comments <span className="font-sans text-base font-medium text-olive/70 tabular-nums">({recipe.commentCount})</span>
            </SectionHeading>
            <section aria-label="comments">
              <CommentsSection recipeId={recipe.id} recipeOwnerId={recipe.user?.id ?? null} />
            </section>
          </>
        )}
      </article>
    </div>
  );
}
