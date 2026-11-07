import { useEffect, useState } from 'react';
import { MessageCircle, Pencil, ShieldX, ThumbsDown, ThumbsUp, Trash2, type LucideIcon } from 'lucide-react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { MFA_SETUP_MESSAGE, isAdminMfaSetupRequired, removeComment } from '@/api/admin';
import { addComment, deleteComment, editComment, getComments } from '@/api/comments';
import { deleteMyReaction, getMyReactions, putMyReaction } from '@/api/reactions';
import { useAuth } from '@/auth/AuthProvider';
import { ApiError } from '@/api/client';
import { Button, Spinner } from '@/components/ui';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { Pagination } from '@/components/Pagination';
import { ReasonDialog } from '@/features/admin/ReasonDialog';
import { ReportButton } from '@/features/reports/ReportButton';
import type { MyReports } from '@/api/reports';
import type { CommentDisplay } from '@/lib/types';

const showError = (e: unknown) =>
  toast.error(e instanceof ApiError ? e.detail : 'Something went wrong');

const fieldClass =
  'min-w-0 flex-1 rounded-lg border border-field bg-surface px-3.5 py-2.5 text-base text-ink transition placeholder:text-ink-soft hover:border-ink-soft focus:border-ink focus:ring-4 focus:ring-ink/15 focus:outline-none sm:text-[15px]';

/** A small round-ended text action under a comment (Edit, Delete). */
const actionClass =
  'inline-flex min-h-10 items-center gap-1 rounded-full px-3 text-[13px] font-medium text-ink-soft transition-colors';

function Avatar({ name, tone = 'tint' }: { name: string; tone?: 'tint' | 'ink' }) {
  return (
    <span
      aria-hidden="true"
      className={`flex size-9 shrink-0 items-center justify-center rounded-full font-serif text-[13px] font-semibold ${
        tone === 'ink' ? 'bg-ink text-on-ink' : 'bg-tint text-ink'
      }`}
    >
      {name.charAt(0).toUpperCase()}
    </span>
  );
}

function formatDate(iso: string | null) {
  if (!iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? null
    : d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
}

/** Query key for the signed-in user's reactions on one recipe's comments (commentId -> isLike). */
const myReactionsKey = (recipeId: string, userId: string | undefined) => ['myReactions', recipeId, userId];

function ReactionButtons({
  comment,
  recipeId,
  mine,
  loading,
}: {
  comment: CommentDisplay;
  recipeId: string;
  /** The user's saved reaction from the server: true = liked, false = disliked, null = none. */
  mine: boolean | null;
  loading: boolean;
}) {
  const { status, profile } = useAuth();
  const queryClient = useQueryClient();
  const setMine = (value: boolean | null) =>
    queryClient.setQueryData<Record<string, boolean>>(myReactionsKey(recipeId, profile?.id), (old) => {
      const next = { ...old };
      if (value === null) delete next[comment.id];
      else next[comment.id] = value;
      return next;
    });
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ['comments'] });

  const react = useMutation({
    mutationFn: (isLike: boolean) => putMyReaction(comment.id, isLike),
    onSuccess: (_d, isLike) => { setMine(isLike); invalidate(); },
    onError: showError,
  });
  const unreact = useMutation({
    mutationFn: () => deleteMyReaction(comment.id),
    onSuccess: () => { setMine(null); invalidate(); },
    onError: showError,
  });

  // Wait for the saved reactions, so clicking an already-liked thumb removes it rather than re-liking.
  const disabled = status !== 'ready' || loading;
  const btn = (isLike: boolean, count: number, label: string, Icon: LucideIcon) => {
    const active = mine === isLike;
    return (
      <button
        aria-label={label}
        aria-pressed={active}
        disabled={disabled}
        onClick={() => (active ? unreact.mutate() : react.mutate(isLike))}
        className={`inline-flex items-center gap-1.5 min-h-10 rounded-full px-3 text-[13px] font-semibold tabular-nums transition-colors disabled:cursor-not-allowed disabled:opacity-40 ${
          active
            ? 'bg-accent/15 text-accent-ink'
            : 'text-ink-soft hover:bg-tint hover:text-ink'
        }`}
      >
        <Icon aria-hidden="true" className="size-3.5" strokeWidth={active ? 2.25 : 1.75} />
        {count}
      </button>
    );
  };
  return (
    <span className="flex gap-1">
      {btn(true, comment.likeCount, `Like comment`, ThumbsUp)}
      {btn(false, comment.dislikeCount, `Dislike comment`, ThumbsDown)}
    </span>
  );
}

export function CommentsSection({
  recipeId,
  recipeOwnerId,
  reported,
  onVisibleIds,
}: {
  recipeId: string;
  recipeOwnerId: string | null;
  /** What the user already reported on this page (from /api/reports/mine), when known. */
  reported?: MyReports;
  /** Tells the page which comment ids are on screen, so it can ask which of them were reported. */
  onVisibleIds?: (ids: string[]) => void;
}) {
  const { status, profile } = useAuth();
  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState('');
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);
  const [reasonError, setReasonError] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: ['comments', recipeId, page],
    queryFn: () => getComments(recipeId, page),
    placeholderData: keepPreviousData,
  });
  // Which comments the signed-in user already liked or disliked, so their thumbs stay highlighted
  // when they come back to this recipe.
  const myReactions = useQuery({
    queryKey: myReactionsKey(recipeId, profile?.id),
    queryFn: () => getMyReactions(recipeId),
    enabled: status === 'ready',
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['comments', recipeId] });
    void queryClient.invalidateQueries({ queryKey: ['recipe', recipeId] });
  };

  const post = useMutation({
    mutationFn: () => addComment(recipeId, draft),
    onSuccess: () => { setDraft(''); setPage(0); invalidate(); },
    onError: showError,
  });
  const saveEdit = useMutation({
    mutationFn: () => editComment(editingId!, editDraft),
    onSuccess: () => { setEditingId(null); invalidate(); },
    onError: showError,
  });
  const remove = useMutation({
    mutationFn: (id: string) => deleteComment(id),
    onSuccess: () => {
      setDeletingId(null);
      if (query.data?.content.length === 1 && page > 0) setPage(page - 1);
      invalidate();
    },
    onError: showError,
  });

  // An admin takes down someone else's comment with a reason; its author is emailed.
  const adminRemove = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => removeComment(id, reason),
    onSuccess: () => {
      setRemovingId(null);
      toast.success('Comment removed');
      if (query.data?.content.length === 1 && page > 0) setPage(page - 1);
      invalidate();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 404) {
        // Already gone: close the dialog and let the refetch drop it.
        setRemovingId(null);
        toast.error('This comment was already removed.');
        invalidate();
      } else if (isAdminMfaSetupRequired(e)) {
        toast.error(MFA_SETUP_MESSAGE);
      } else if (e instanceof ApiError && e.errors?.reason) {
        setReasonError(e.errors.reason);
      } else {
        showError(e);
      }
    },
  });

  const visibleIds = query.data?.content.map((c) => c.id);
  const visibleKey = visibleIds?.join(',');
  useEffect(() => {
    if (visibleIds) onVisibleIds?.(visibleIds);
    // visibleKey stands in for the array, which is new on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visibleKey]);

  if (query.isPending) return <Spinner />;
  if (query.isError) return <p className="text-[15px] text-danger">Couldn't load comments.</p>;

  return (
    <div>
      {status === 'ready' ? (
        <form
          className="mb-8 flex items-center gap-3 rounded-card border border-rule bg-surface p-3"
          onSubmit={(e) => { e.preventDefault(); if (draft.trim()) post.mutate(); }}
        >
          {profile && <Avatar name={profile.firstName} tone="ink" />}
          <input
            aria-label="Add a comment"
            placeholder="Add a comment…"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            maxLength={5000}
            className={fieldClass}
          />
          <Button type="submit" disabled={post.isPending || !draft.trim()}>Post</Button>
        </form>
      ) : (
        <p className="mb-8 flex items-center gap-2.5 rounded-card bg-tint px-4 py-3 text-[15px] text-ink">
          <MessageCircle aria-hidden="true" className="size-4 shrink-0 text-ink-soft" />
          Sign in to join the conversation.
        </p>
      )}

      <ul
        aria-busy={query.isPlaceholderData}
        className={`divide-y divide-rule transition-opacity${query.isPlaceholderData ? ' opacity-50' : ''}`}
      >
        {query.data.content.map((comment) => {
          const isAuthor = profile?.id === comment.user.id;
          // An admin moderates strangers' comments with Remove (reason + email), so they get one button, not two.
          const canRemove = !!profile?.isAdmin && !isAuthor;
          const canDelete = isAuthor || (!canRemove && recipeOwnerId !== null && profile?.id === recipeOwnerId);
          const date = formatDate(comment.createdAt);
          return (
            <li key={comment.id} className="flex gap-3 py-5">
              <Avatar name={comment.user.firstName} />
              <div className="min-w-0 flex-1">
                <div>
                  <p className="flex flex-wrap items-baseline gap-x-2 text-[15px]">
                    <span className="font-semibold text-ink">
                      {comment.user.firstName} {comment.user.lastName}
                    </span>
                    {date && comment.createdAt && (
                      <time dateTime={comment.createdAt} className="text-[13px] text-ink-soft">{date}</time>
                    )}
                  </p>
                  {editingId === comment.id ? (
                    <form
                      className="mt-2 flex flex-wrap gap-2"
                      onSubmit={(e) => { e.preventDefault(); saveEdit.mutate(); }}
                    >
                      <input
                        aria-label="Edit comment"
                        value={editDraft}
                        onChange={(e) => setEditDraft(e.target.value)}
                        maxLength={5000}
                        className={`${fieldClass} basis-56`}
                      />
                      <div className="flex gap-2">
                        <Button type="submit" disabled={!editDraft.trim() || saveEdit.isPending}>Save</Button>
                        <Button type="button" variant="outline" onClick={() => setEditingId(null)}>Cancel</Button>
                      </div>
                    </form>
                  ) : (
                    <p className="mt-1 max-w-prose text-base leading-relaxed break-words whitespace-pre-line text-ink">
                      {comment.body}
                    </p>
                  )}
                  {comment.hidden && (
                    <p className="mt-1 text-[13px] text-ink-soft">Hidden while an admin looks at reports.</p>
                  )}
                </div>
                <div className="mt-2 flex flex-wrap items-center gap-1 -ml-2.5">
                  <ReactionButtons
                    comment={comment}
                    recipeId={recipeId}
                    mine={myReactions.data?.[comment.id] ?? null}
                    loading={myReactions.isLoading}
                  />
                  {isAuthor && editingId !== comment.id && (
                    <button
                      aria-label={`Edit comment by ${comment.user.firstName} ${comment.user.lastName}`}
                      className={`${actionClass} hover:bg-tint hover:text-ink`}
                      onClick={() => { setEditingId(comment.id); setEditDraft(comment.body); }}
                    >
                      <Pencil aria-hidden="true" className="size-3" />
                      Edit
                    </button>
                  )}
                  {canDelete && (
                    <button
                      aria-label={`Delete comment by ${comment.user.firstName} ${comment.user.lastName}`}
                      className={`${actionClass} hover:bg-danger/10 hover:text-danger`}
                      onClick={() => setDeletingId(comment.id)}
                    >
                      <Trash2 aria-hidden="true" className="size-3" />
                      Delete
                    </button>
                  )}
                  {status === 'ready' && !isAuthor && !comment.hidden && (
                    <ReportButton
                      variant="inline"
                      title="Report this comment"
                      label={`comment by ${comment.user.firstName} ${comment.user.lastName}`}
                      target={{ commentId: comment.id }}
                      reported={reported?.commentIds.includes(comment.id) ?? false}
                    />
                  )}
                  {canRemove && (
                    <button
                      aria-label={`Remove comment by ${comment.user.firstName} ${comment.user.lastName}`}
                      className={`${actionClass} hover:bg-danger/10 hover:text-danger`}
                      onClick={() => { setReasonError(null); setRemovingId(comment.id); }}
                    >
                      <ShieldX aria-hidden="true" className="size-3" />
                      Remove
                    </button>
                  )}
                </div>
              </div>
            </li>
          );
        })}
      </ul>
      {query.data.content.length === 0 && (
        <p className="rounded-card border border-dashed border-rule px-4 py-8 text-center text-[15px] text-ink-soft">
          No comments yet.
        </p>
      )}

      <Pagination page={query.data.page.number} totalPages={query.data.page.totalPages} onPage={setPage} />

      <ConfirmDialog
        open={deletingId !== null}
        title="Delete comment?"
        description="This can't be undone."
        confirmLabel="Confirm"
        busy={remove.isPending}
        onConfirm={() => deletingId && remove.mutate(deletingId)}
        onCancel={() => setDeletingId(null)}
      />
      <ReasonDialog
        open={removingId !== null}
        title="Remove this comment?"
        description="The author will get an email with your reason."
        confirmLabel="Remove comment"
        busy={adminRemove.isPending}
        error={reasonError}
        onCancel={() => setRemovingId(null)}
        onEdit={() => setReasonError(null)}
        onSubmit={(reason) => {
          if (!removingId) return;
          setReasonError(null);
          adminRemove.mutate({ id: removingId, reason });
        }}
      />
    </div>
  );
}
