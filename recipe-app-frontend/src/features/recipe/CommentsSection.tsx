import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { addComment, deleteComment, editComment, getComments } from '@/api/comments';
import { deleteMyReaction, putMyReaction } from '@/api/reactions';
import { useAuth } from '@/auth/AuthProvider';
import { ApiError } from '@/api/client';
import { Button, Spinner } from '@/components/ui';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { Pagination } from '@/components/Pagination';
import type { CommentDisplay } from '@/lib/types';

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
  });
  const unreact = useMutation({
    mutationFn: () => deleteMyReaction(comment.id),
    onSuccess: () => { setMine(null); invalidate(); },
  });

  const disabled = status !== 'ready';
  const btn = (isLike: boolean, count: number, label: string, glyph: string) => (
    <button
      aria-label={label}
      disabled={disabled}
      onClick={() => (mine === isLike ? unreact.mutate() : react.mutate(isLike))}
      className={`text-xs ${mine === isLike ? 'font-bold text-accent' : 'text-muted hover:text-ink'} disabled:opacity-40`}
    >
      {glyph} {count}
    </button>
  );
  return (
    <span className="flex gap-3">
      {btn(true, comment.likeCount, `Like comment`, '👍')}
      {btn(false, comment.dislikeCount, `Dislike comment`, '👎')}
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
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['comments', recipeId] });
    void queryClient.invalidateQueries({ queryKey: ['recipe', recipeId] });
  };
  const onError = (e: unknown) => toast.error(e instanceof ApiError ? e.detail : 'Something went wrong');

  const post = useMutation({
    mutationFn: () => addComment(recipeId, draft),
    onSuccess: () => { setDraft(''); invalidate(); },
    onError,
  });
  const saveEdit = useMutation({
    mutationFn: () => editComment(editingId!, editDraft),
    onSuccess: () => { setEditingId(null); invalidate(); },
    onError,
  });
  const remove = useMutation({
    mutationFn: (id: string) => deleteComment(id),
    onSuccess: () => { setDeletingId(null); invalidate(); },
    onError,
  });

  if (query.isPending) return <Spinner />;
  if (query.isError) return <p className="text-sm text-red-600">Couldn't load comments.</p>;

  return (
    <div>
      {status === 'ready' ? (
        <form
          className="mb-6 flex gap-2"
          onSubmit={(e) => { e.preventDefault(); if (draft.trim()) post.mutate(); }}
        >
          <input
            placeholder="Add a comment…"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            maxLength={5000}
            className="flex-1 border border-hairline px-3 py-2 text-sm focus:border-ink focus:outline-none"
          />
          <Button type="submit" disabled={post.isPending || !draft.trim()}>Post</Button>
        </form>
      ) : (
        <p className="mb-6 text-sm text-muted">Sign in to join the conversation.</p>
      )}

      <ul className="space-y-4">
        {query.data.content.map((comment) => {
          const isAuthor = profile?.id === comment.user.id;
          const canDelete = isAuthor || (recipeOwnerId !== null && profile?.id === recipeOwnerId);
          return (
            <li key={comment.id} className="border-b border-hairline pb-3">
              <p className="text-xs font-bold tracking-wide uppercase">
                {comment.user.firstName} {comment.user.lastName}
              </p>
              {editingId === comment.id ? (
                <form
                  className="mt-1 flex gap-2"
                  onSubmit={(e) => { e.preventDefault(); saveEdit.mutate(); }}
                >
                  <input
                    value={editDraft}
                    onChange={(e) => setEditDraft(e.target.value)}
                    className="flex-1 border border-hairline px-2 py-1 text-sm"
                  />
                  <Button type="submit">Save</Button>
                  <Button type="button" variant="outline" onClick={() => setEditingId(null)}>Cancel</Button>
                </form>
              ) : (
                <p className="mt-1 text-sm">{comment.body}</p>
              )}
              <div className="mt-2 flex items-center gap-4">
                <ReactionButtons comment={comment} />
                {isAuthor && editingId !== comment.id && (
                  <button
                    className="text-xs text-muted underline hover:text-ink"
                    onClick={() => { setEditingId(comment.id); setEditDraft(comment.body); }}
                  >
                    Edit
                  </button>
                )}
                {canDelete && (
                  <button
                    className="text-xs text-muted underline hover:text-red-600"
                    onClick={() => setDeletingId(comment.id)}
                  >
                    Delete
                  </button>
                )}
              </div>
            </li>
          );
        })}
        {query.data.content.length === 0 && <p className="text-sm text-muted">No comments yet.</p>}
      </ul>

      <Pagination page={query.data.page.number} totalPages={query.data.page.totalPages} onPage={setPage} />

      <ConfirmDialog
        open={deletingId !== null}
        title="Delete comment?"
        description="This can't be undone."
        confirmLabel="Confirm"
        onConfirm={() => deletingId && remove.mutate(deletingId)}
        onCancel={() => setDeletingId(null)}
      />
    </div>
  );
}
