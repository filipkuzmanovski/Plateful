import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CircleAlert, SearchX } from 'lucide-react';
import {
  approveRecipe,
  getPendingRecipes,
  getReviewRecipe,
  isAdminMfaSetupRequired,
  rejectRecipe,
} from '@/api/admin';
import { ApiError } from '@/api/client';
import { Button, EmptyState, Spinner } from '@/components/ui';
import { RecipeBody } from '@/features/recipe/RecipeBody';
import { AdminMfaPanel } from './AdminMfaPanel';
import { RejectDialog } from './RejectDialog';

const CHANGED = 'This recipe changed. Reload to review the latest version.';
const REVIEWED = 'This recipe was already reviewed.';
const GONE = 'This recipe is no longer in the queue.';

/** Why a decision did not go through: the recipe was edited, was decided already, is gone, or the admin lacks 2FA. */
type Problem = 'changed' | 'reviewed' | 'gone' | 'mfa';

/** A decision on one revision. Without a `reason` it is an approval. */
interface Decision { revision: number; reason?: string }

const backLink = (
  <Link to="/admin/review" className="text-sm font-semibold text-olive underline underline-offset-4 hover:decoration-saffron">
    Back to the queue
  </Link>
);

function problemOf(e: unknown): Problem | null {
  if (isAdminMfaSetupRequired(e)) return 'mfa';
  if (!(e instanceof ApiError)) return null;
  if (e.code === 'recipe_changed') return 'changed';
  if (e.code === 'not_pending') return 'reviewed';
  return e.status === 404 ? 'gone' : null;
}

function ReviewRecipeView({ id }: { id: string }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ['admin', 'recipe', id], queryFn: () => getReviewRecipe(id),
    retry: false,
    // The revision the admin read is what a decision sends. Only Reload (or a 409) may change it, never a focus or
    // reconnect refetch: that would swap in the author's newer edit and defeat the recipe_changed guard.
    // gcTime 0: leaving the page drops it, so reopening within the usual 5 minutes cannot show a stale revision.
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
  const [problem, setProblem] = useState<Problem | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [reasonError, setReasonError] = useState<string | null>(null);
  // Set once a decision went through, so the decided recipe is not shown again while the next one is looked up.
  const [leaving, setLeaving] = useState(false);

  const decide = useMutation({
    mutationFn: ({ revision, reason }: Decision) =>
      reason === undefined ? approveRecipe(id, revision) : rejectRecipe(id, revision, reason),
    onSuccess: async (_data, { reason }) => {
      toast.success(reason === undefined ? 'Recipe approved' : 'Recipe rejected');
      setLeaving(true);
      queryClient.removeQueries({ queryKey: ['admin', 'recipe', id] });
      for (const queryKey of [['admin'], ['recipes'], ['recipe', id], ['userRecipes'], ['savedRecipes']]) {
        void queryClient.invalidateQueries({ queryKey });
      }
      // On to the oldest recipe still waiting, or back to the queue when there is none.
      const next = await getPendingRecipes(0).then((p) => p.content[0]?.id ?? null, () => null);
      navigate(next ? `/admin/review/${next}` : '/admin/review');
    },
    onError: (e) => {
      const found = problemOf(e);
      if (found) {
        setRejecting(false);
        setProblem(found);
        if (found === 'reviewed' || found === 'gone') {
          // The queue and the badge no longer match the server.
          void queryClient.invalidateQueries({ queryKey: ['admin', 'pending'] });
          void queryClient.invalidateQueries({ queryKey: ['admin', 'pendingCount'] });
        }
      } else if (e instanceof ApiError && e.errors?.reason) {
        setReasonError(e.errors.reason);
      } else {
        toast.error(e instanceof ApiError ? e.detail : "Couldn't save the decision");
      }
    },
  });

  if (leaving) return <Spinner />;
  if (problem === 'mfa' || (query.isError && isAdminMfaSetupRequired(query.error))) return <AdminMfaPanel />;
  if (query.isPending) return <Spinner />;
  // A failed Reload keeps the recipe on screen (query.data survives a failed refetch).
  if (query.isError && !query.data) {
    const notFound = query.error instanceof ApiError && query.error.status === 404;
    return (
      <EmptyState icon={notFound ? SearchX : CircleAlert} action={backLink}>
        {notFound ? GONE : "Couldn't load this recipe."}
      </EmptyState>
    );
  }

  const recipe = query.data;
  const revision = recipe.review?.revision;
  // A recipe that is no longer pending (decided by someone else) offers no decision either.
  const decided = problem === 'reviewed' || problem === 'gone' || recipe.review?.status !== 'pending';
  const message = decided ? (problem === 'gone' ? GONE : REVIEWED) : problem === 'changed' ? CHANGED : null;

  return (
    <>
      {!decided && <div className="mb-6">{backLink}</div>}
      <RecipeBody recipe={recipe} />
      <div className="sticky bottom-0 z-20 mt-10 -mx-4 border-t border-sand bg-paper/95 px-4 py-3 backdrop-blur-md sm:-mx-6 sm:px-6">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3">
          {message && (
            <div role="alert" className="min-w-0 text-sm font-medium text-danger">
              {message}
            </div>
          )}
          <div className="ml-auto flex flex-wrap items-center gap-2">
            {problem === 'changed' && (
              <Button
                variant="outline"
                disabled={query.isFetching}
                onClick={() =>
                  void query.refetch().then((r) => {
                    if (r.isSuccess) setProblem(null);
                    else toast.error("Couldn't reload this recipe.");
                  })
                }
              >
                Reload
              </Button>
            )}
            {decided || revision === undefined ? (
              backLink
            ) : (
              <>
                <Button
                  variant="danger"
                  disabled={decide.isPending || problem === 'changed'}
                  onClick={() => { setReasonError(null); setRejecting(true); }}
                >
                  Reject
                </Button>
                <Button disabled={decide.isPending || problem === 'changed'} onClick={() => decide.mutate({ revision })}>
                  Approve
                </Button>
              </>
            )}
          </div>
        </div>
      </div>
      <RejectDialog
        open={rejecting}
        busy={decide.isPending}
        error={reasonError}
        onCancel={() => setRejecting(false)}
        onEdit={() => setReasonError(null)}
        onSubmit={(reason) => {
          if (revision === undefined) return;
          setReasonError(null);
          decide.mutate({ revision, reason });
        }}
      />
    </>
  );
}

/** One recipe awaiting review, shown as readers will see it, with the decision bar. */
export default function ReviewRecipePage() {
  const { id } = useParams<{ id: string }>();
  // Keyed by id so moving to the next recipe starts with no leftover message or open dialog.
  return <ReviewRecipeView key={id} id={id!} />;
}
