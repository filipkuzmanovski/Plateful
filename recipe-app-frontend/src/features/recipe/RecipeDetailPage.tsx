import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CircleAlert, Pencil, SearchX, ShieldX, Trash2 } from 'lucide-react';
import { deleteRecipe, getRecipe } from '@/api/recipes';
import { removeRecipe as removeRecipeAsAdmin } from '@/api/admin';
import { ApiError } from '@/api/client';
import { deletePhotos, pathFromPublicUrl } from '@/api/photos';
import { deleteVideoFiles, pathFromPublicVideoUrl } from '@/api/videos';
import { useAuth } from '@/auth/AuthProvider';
import { Button, EmptyState, SectionHeading, Spinner } from '@/components/ui';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { RatingBox } from './RatingBox';
import { CommentsSection } from './CommentsSection';
import { RecipeBody } from './RecipeBody';
import { SaveRecipeButton } from '@/features/saved/SaveRecipeButton';
import { HiddenRecipePage } from './HiddenRecipePage';
import { ReviewStatusBanner } from '@/features/review/ReviewStatusBanner';
import { ReasonDialog } from '@/features/admin/ReasonDialog';
import type { RecipeDisplay } from '@/lib/types';

/** Best-effort: once a recipe row is gone its photos and video are orphans; the helpers never throw. */
function cleanUpMedia(recipe: RecipeDisplay | undefined) {
  const paths = (recipe?.photos ?? []).map(pathFromPublicUrl).filter((p): p is string => p !== null);
  if (paths.length > 0) void deletePhotos(paths);
  const video = recipe?.video;
  const videoFiles = video
    ? [video.url, video.posterUrl].map(pathFromPublicVideoUrl).filter((p): p is string => p !== null)
    : [];
  if (videoFiles.length > 0) void deleteVideoFiles(videoFiles);
}

export default function RecipeDetailPage() {
  const { id } = useParams<{ id: string }>();
  const query = useQuery({ queryKey: ['recipe', id], queryFn: () => getRecipe(id!), retry: false });
  const { profile, refreshProfile } = useAuth();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [removing, setRemoving] = useState(false);
  const [reasonError, setReasonError] = useState<string | null>(null);
  const removeRecipe = useMutation({
    mutationFn: (recipeId: string) => deleteRecipe(recipeId),
    onSuccess: (_data, recipeId) => {
      cleanUpMedia(query.data);
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

  // An admin takes the recipe down with a reason; the author is emailed. The recipe row and its ratings are gone.
  const adminRemove = useMutation({
    mutationFn: ({ recipeId, reason }: { recipeId: string; reason: string }) => removeRecipeAsAdmin(recipeId, reason),
    onSuccess: (_data, { recipeId }) => {
      cleanUpMedia(query.data);
      toast.success('Recipe removed');
      setRemoving(false);
      navigate('/');
      queryClient.removeQueries({ queryKey: ['recipe', recipeId] });
      for (const queryKey of [['recipes'], ['userRecipes'], ['savedRecipes'], ['savedIds'], ['admin']]) {
        void queryClient.invalidateQueries({ queryKey });
      }
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 404) {
        setRemoving(false);
        toast.error('This recipe was already removed.');
        navigate('/');
      } else if (e instanceof ApiError && e.errors?.reason) {
        setReasonError(e.errors.reason);
      } else {
        toast.error(e instanceof ApiError ? e.detail : "Couldn't remove the recipe");
      }
    },
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
  const canRemove = !!profile?.isAdmin && !isOwner;
  // Only an approved recipe is open to feedback; the API refuses writes on the others (409 recipe_not_approved).
  const acceptsFeedback = !recipe.review || recipe.review.status === 'approved';

  return (
    <RecipeBody
      recipe={recipe}
      asideExtra={
        acceptsFeedback && (
          <div className="border-t border-sand pt-5">
            <RatingBox recipeId={recipe.id} />
          </div>
        )
      }
      banner={isOwner && recipe.review && <ReviewStatusBanner review={recipe.review} recipeId={recipe.id} />}
      actions={
        <>
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
          {canRemove && (
            <>
              <Button
                variant="danger"
                className="!px-4 !py-2"
                onClick={() => { setReasonError(null); setRemoving(true); }}
              >
                <ShieldX aria-hidden="true" className="size-3.5" />
                Remove
              </Button>
              <ReasonDialog
                open={removing}
                title="Remove this recipe?"
                description="The author will get an email with your reason."
                confirmLabel="Remove recipe"
                busy={adminRemove.isPending}
                error={reasonError}
                onCancel={() => setRemoving(false)}
                onEdit={() => setReasonError(null)}
                onSubmit={(reason) => { setReasonError(null); adminRemove.mutate({ recipeId: recipe.id, reason }); }}
              />
            </>
          )}
        </>
      }
      after={
        acceptsFeedback && (
          <>
            <SectionHeading>
              Comments <span className="font-sans text-base font-medium text-olive/70 tabular-nums">({recipe.commentCount})</span>
            </SectionHeading>
            <section aria-label="comments">
              <CommentsSection recipeId={recipe.id} recipeOwnerId={recipe.user?.id ?? null} />
            </section>
          </>
        )
      }
    />
  );
}
