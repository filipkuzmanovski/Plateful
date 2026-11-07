import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CircleAlert, Pencil, SearchX, ShieldX, Trash2 } from 'lucide-react';
import { deleteRecipe, getRecipe } from '@/api/recipes';
import { MFA_SETUP_MESSAGE, isAdminMfaSetupRequired, removeRecipe as removeRecipeAsAdmin } from '@/api/admin';
import { ApiError } from '@/api/client';
import { deletePhotos, pathFromPublicUrl } from '@/api/photos';
import { deleteVideoFiles, pathFromPublicVideoUrl } from '@/api/videos';
import { useAuth } from '@/auth/AuthProvider';
import { Button, EmptyState, SectionHeading, Spinner } from '@/components/ui';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { RatingBox } from './RatingBox';
import { CommentsSection } from './CommentsSection';
import { RecipeBody } from './RecipeBody';
import { ShareRecipe } from './ShareRecipe';
import { SaveRecipeButton } from '@/features/saved/SaveRecipeButton';
import { HiddenRecipePage } from './HiddenRecipePage';
import { isLive } from '@/lib/live';
import { ReviewStatusBanner } from '@/features/review/ReviewStatusBanner';
import { ReasonDialog } from '@/features/admin/ReasonDialog';
import { LikeButton } from '@/features/likes/LikeButton';
import { ReportButton } from '@/features/reports/ReportButton';
import { useMyReports } from '@/features/reports/useMyReports';
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
  // Set once the recipe is known to be gone, so dropping its cached query is not followed by a refetch of it.
  const [gone, setGone] = useState(false);
  const query = useQuery({ queryKey: ['recipe', id], queryFn: () => getRecipe(id!), retry: false, enabled: !gone });
  const { status, profile, refreshProfile } = useAuth();
  const [commentIds, setCommentIds] = useState<string[] | null>(null);
  const myReports = useMyReports(id!, commentIds);
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

  /** The recipe no longer exists: leave the page, drop its cache and mark every list that may show it stale. */
  const forgetRecipe = (recipeId: string) => {
    setGone(true);
    navigate('/');
    queryClient.removeQueries({ queryKey: ['recipe', recipeId] });
    for (const queryKey of [['recipes'], ['userRecipes'], ['savedRecipes'], ['savedIds'], ['admin']]) {
      void queryClient.invalidateQueries({ queryKey });
    }
  };

  // An admin takes the recipe down with a reason; the author is emailed.
  const adminRemove = useMutation({
    mutationFn: ({ recipeId, reason }: { recipeId: string; reason: string }) => removeRecipeAsAdmin(recipeId, reason),
    onSuccess: (_data, { recipeId }) => {
      cleanUpMedia(query.data);
      toast.success('Recipe removed');
      setRemoving(false);
      forgetRecipe(recipeId);
    },
    onError: (e, { recipeId }) => {
      if (e instanceof ApiError && e.status === 404) {
        // Someone else removed it first: nothing of ours to clean up, but the caches still hold it.
        setRemoving(false);
        toast.error('This recipe was already removed.');
        forgetRecipe(recipeId);
      } else if (isAdminMfaSetupRequired(e)) {
        toast.error(MFA_SETUP_MESSAGE);
      } else if (e instanceof ApiError && e.errors?.reason) {
        setReasonError(e.errors.reason);
      } else {
        toast.error(e instanceof ApiError ? e.detail : "Couldn't remove the recipe");
      }
    },
  });

  if (query.isPending) return <Spinner />;
  const error = query.error;
  const notFound = query.isError && error instanceof ApiError && error.status === 404;
  // A failed background refetch (the estimate poll) keeps the recipe on screen; only a missing one leaves the page.
  if (query.isError && (query.data === undefined || notFound)) {
    // A recipe approved once but hidden now answers 404 with a code and its last approved title.
    if (notFound && (error.code === 'under_review' || error.code === 'unavailable') && error.approvedTitle) {
      return <HiddenRecipePage recipeId={id!} approvedTitle={error.approvedTitle} state={error.code} />;
    }
    return (
      <EmptyState
        icon={notFound ? SearchX : CircleAlert}
        action={
          <Link to="/" className="text-[15px] font-semibold text-ink underline decoration-rule underline-offset-4 hover:decoration-ink">
            Back to all recipes
          </Link>
        }
      >
        {notFound ? 'Recipe not found.' : "Couldn't load this recipe."}
      </EmptyState>
    );
  }
  const recipe = query.data!;
  const isOwner = !!profile && recipe.user?.id === profile.id;
  const canRemove = !!profile?.isAdmin && !isOwner;
  // Only an approved recipe is open to feedback; the API refuses writes on the others (409 recipe_not_approved).
  const acceptsFeedback = isLive(recipe);

  return (
    <RecipeBody
      recipe={recipe}
      asideExtra={
        acceptsFeedback && (
          <div className="border-t border-rule pt-5">
            <RatingBox recipeId={recipe.id} />
          </div>
        )
      }
      banner={isOwner && recipe.review && <ReviewStatusBanner review={recipe.review} recipeId={recipe.id} />}
      actions={
        <>
          <LikeButton recipe={recipe} variant="page" />
          <SaveRecipeButton recipeId={recipe.id} />
          {acceptsFeedback && <ShareRecipe recipe={recipe} />}
          {status === 'ready' && !isOwner && acceptsFeedback && (
            <ReportButton
              variant="page"
              title="Report this recipe"
              target={{ recipeId: recipe.id }}
              reported={myReports.data?.recipeIds.includes(recipe.id) ?? false}
            />
          )}
          {isOwner && (
            <>
              <Link
                to={`/recipes/${recipe.id}/edit`}
                className="inline-flex min-h-11 items-center gap-2 rounded-full border border-field bg-surface px-4 text-[15px] font-semibold text-ink transition-colors hover:bg-tint"
              >
                <Pencil aria-hidden="true" className="size-3.5" />
                Edit
              </Link>
              <Button variant="danger" onClick={() => setConfirmDelete(true)}>
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
              Comments <span className="font-sans text-base font-medium text-ink-soft tabular-nums">({recipe.commentCount})</span>
            </SectionHeading>
            <section aria-label="comments">
              <CommentsSection
                recipeId={recipe.id}
                recipeOwnerId={recipe.user?.id ?? null}
                reported={myReports.data}
                onVisibleIds={setCommentIds}
              />
            </section>
          </>
        )
      }
    />
  );
}
