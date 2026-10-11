import { useState } from 'react';
import { MessageCircle, Pencil, ThumbsDown, ThumbsUp, Trash2, type LucideIcon } from 'lucide-react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { addComment, deleteComment, editComment, getComments } from '@/api/comments';
import { deleteMyReaction, putMyReaction } from '@/api/reactions';
import { useAuth } from '@/auth/AuthProvider';
import { ApiError } from '@/api/client';
import { Button, Spinner } from '@/components/ui';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { Pagination } from '@/components/Pagination';
import type { CommentDisplay } from '@/lib/types';

const showError = (e: unknown) =>
  toast.error(e instanceof ApiError ? e.detail : 'Something went wrong');

const fieldClass =
  'min-w-0 flex-1 rounded-xl border border-sand bg-paper px-3.5 py-2.5 text-sm text-olive shadow-[inset_0_1px_2px_rgb(47_58_43/0.04)] transition placeholder:text-olive/70 hover:border-olive/30 focus:border-olive/60 focus:ring-4 focus:ring-saffron/20 focus:outline-none';

/** A small round-ended text action under a comment (Edit, Delete). */
const actionClass =
  'inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-medium text-olive/70 transition-colors';

function Avatar({ name, tone = 'sand' }: { name: string; tone?: 'sand' | 'olive' }) {
  return (
    <span
      aria-hidden="true"
      className={`flex size-9 shrink-0 items-center justify-center rounded-full font-serif text-sm font-semibold ${
        tone === 'olive' ? 'bg-olive text-cream' : 'bg-sand text-olive'
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

function ReactionButtons({ comment }: { comment: CommentDisplay }) {
  const { status } = useAuth();
  // The API has no "my reaction" read endpoint; we track the reaction made
  // in THIS session locally. Unknown initial state: first click always PUTs.
  const [mine, setMine] = useState<boolean | null>(null);
  const queryClient = useQueryClient();
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

  const disabled = status !== 'ready';
  const btn = (isLike: boolean, count: number, label: string, Icon: LucideIcon) => {
    const active = mine === isLike;
    return (
      <button
        aria-label={label}
        disabled={disabled}
        onClick={() => (active ? unreact.mutate() : react.mutate(isLike))}
        className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold tabular-nums transition-colors disabled:cursor-not-allowed disabled:opacity-40 ${
          active
            ? isLike
              ? 'bg-saffron/15 text-saffron-deep'
              : 'bg-terracotta/10 text-terracotta-deep'
            : 'text-olive/70 hover:bg-sand/60 hover:text-olive'
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
}: {
  recipeId: string;
  recipeOwnerId: string | null;
}) {
  const { status, profile } = useAuth();
  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState('');
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: ['comments', recipeId, page],
    queryFn: () => getComments(recipeId, page),
    placeholderData: keepPreviousData,
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

  if (query.isPending) return <Spinner />;
  if (query.isError) return <p className="text-sm text-danger">Couldn't load comments.</p>;

  return (
    <div>
      {status === 'ready' ? (
        <form
          className="mb-8 flex items-center gap-3"
          onSubmit={(e) => { e.preventDefault(); if (draft.trim()) post.mutate(); }}
        >
          {profile && <Avatar name={profile.firstName} tone="olive" />}
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
        <p className="mb-8 flex items-center gap-2.5 rounded-2xl bg-sand/40 px-4 py-3 text-sm text-olive">
          <MessageCircle aria-hidden="true" className="size-4 shrink-0 text-olive/60" />
          Sign in to join the conversation.
        </p>
      )}

      <ul
        aria-busy={query.isPlaceholderData}
        className={`space-y-5 transition-opacity${query.isPlaceholderData ? ' opacity-50' : ''}`}
      >
        {query.data.content.map((comment) => {
          const isAuthor = profile?.id === comment.user.id;
          const canDelete = isAuthor || (recipeOwnerId !== null && profile?.id === recipeOwnerId);
          const date = formatDate(comment.createdAt);
          return (
            <li key={comment.id} className="flex gap-3">
              <Avatar name={comment.user.firstName} />
              <div className="min-w-0 flex-1">
                <div className="rounded-2xl rounded-tl-md bg-paper px-4 py-3 shadow-card ring-1 ring-sand/60">
                  <p className="flex flex-wrap items-baseline gap-x-2 text-sm">
                    <span className="font-semibold text-olive">
                      {comment.user.firstName} {comment.user.lastName}
                    </span>
                    {date && comment.createdAt && (
                      <time dateTime={comment.createdAt} className="text-xs text-olive/70">{date}</time>
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
                    <p className="mt-1 text-[15px] leading-relaxed break-words whitespace-pre-line text-olive">
                      {comment.body}
                    </p>
                  )}
                </div>
                <div className="mt-1.5 flex flex-wrap items-center gap-1 pl-1">
                  <ReactionButtons comment={comment} />
                  {isAuthor && editingId !== comment.id && (
                    <button
                      aria-label={`Edit comment by ${comment.user.firstName} ${comment.user.lastName}`}
                      className={`${actionClass} hover:bg-sand/60 hover:text-olive`}
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
                </div>
              </div>
            </li>
          );
        })}
      </ul>
      {query.data.content.length === 0 && (
        <p className="rounded-2xl border border-dashed border-sand px-4 py-8 text-center text-sm text-olive/70">
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
    </div>
  );
}
