import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CircleAlert, ExternalLink, Flag, ShieldX } from 'lucide-react';
import {
  MFA_SETUP_MESSAGE,
  dismissCommentReports,
  dismissRecipeReports,
  getReports,
  isAdminMfaSetupRequired,
  removeComment,
  removeRecipe,
  type ReportedItem,
} from '@/api/admin';
import { ApiError } from '@/api/client';
import type { ReportReason } from '@/api/reports';
import { Button, EmptyState, Spinner } from '@/components/ui';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { Pagination } from '@/components/Pagination';
import { AdminMfaPanel } from './AdminMfaPanel';
import { ReasonDialog } from './ReasonDialog';

const ALREADY_HANDLED = 'This was already handled.';

const REASON_LABELS: Record<ReportReason, string> = {
  spam: 'Spam',
  offensive: 'Offensive or hateful',
  unsafe: 'Unsafe food advice',
  not_food: 'Not food related',
  other: 'Other',
};

const formatDate = (iso: string) => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString(undefined, { dateStyle: 'medium' });
};

type Action = { type: 'dismiss' | 'remove'; item: ReportedItem };

const itemKey = (i: ReportedItem) => `${i.kind}-${i.id}`;

/** A 404 (gone) or 409 (no open reports left) means someone else got there first. */
const isAlreadyHandled = (e: unknown) => e instanceof ApiError && (e.status === 404 || e.status === 409);

function ReportEntry({ item, onDismiss, onRemove }: { item: ReportedItem; onDismiss: () => void; onRemove: () => void }) {
  const people = item.openCount === 1 ? '1 person' : `${item.openCount} people`;
  const posted = formatDate(item.postedAt);
  return (
    <article className="rounded-card border border-rule bg-surface p-4 sm:p-5">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[13px] font-semibold tracking-wide text-ink-soft uppercase">
          {item.kind === 'recipe' ? 'Recipe' : 'Comment'}
        </span>
        {item.hidden && (
          <span className="rounded-full border border-danger/50 px-2 py-0.5 text-[12px] font-semibold text-danger">Hidden</span>
        )}
      </div>
      {item.kind === 'recipe' ? (
        <h2 className="mt-1.5 font-serif text-[22px] leading-snug font-semibold break-words text-ink">{item.text}</h2>
      ) : (
        <p className="mt-1.5 text-base leading-relaxed break-words whitespace-pre-line text-ink">{item.text}</p>
      )}
      <p className="mt-1.5 text-[14px] text-ink-soft">
        {item.authorName ?? 'Imported recipe'}
        {posted && ` · ${posted}`}
      </p>
      <p className="mt-3 text-[15px] font-semibold text-ink">Reported by {people}</p>
      <ul className="mt-2 space-y-2">
        {item.reports.map((r, idx) => (
          <li key={idx} className="rounded-lg bg-tint px-3 py-2 text-[14px] text-ink">
            <div className="flex flex-wrap items-baseline justify-between gap-x-3">
              <span className="font-semibold">{REASON_LABELS[r.reason] ?? r.reason}</span>
              <span className="text-ink-soft">{formatDate(r.createdAt)}</span>
            </div>
            {r.note && <p className="mt-0.5 break-words text-ink-soft">{r.note}</p>}
          </li>
        ))}
      </ul>
      <div className="mt-4 flex flex-wrap gap-2">
        <Link
          to={`/recipes/${item.recipeId}`}
          className="inline-flex min-h-11 items-center justify-center gap-2 rounded-full border border-field bg-surface px-5 py-2 text-[15px] font-semibold text-ink transition-colors hover:bg-tint"
        >
          <ExternalLink aria-hidden="true" className="size-4" />
          Open
        </Link>
        <Button variant="outline" onClick={onDismiss}>Dismiss</Button>
        <Button variant="danger" onClick={onRemove}>
          <ShieldX aria-hidden="true" className="size-4" />
          Remove
        </Button>
      </div>
    </article>
  );
}

/** The admin queue of reported recipes and comments: open the item, dismiss the reports, or remove it. */
export default function ReportsPage() {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [action, setAction] = useState<Action | null>(null);
  const [reasonError, setReasonError] = useState<string | null>(null);
  const [handled, setHandled] = useState(false);

  const query = useQuery({ queryKey: ['admin', 'reports', page], queryFn: () => getReports(page), retry: false });

  // Removing the last entry of a later page leaves it empty: step back.
  const empty = query.data?.content.length === 0;
  useEffect(() => {
    if (empty && page > 0) setPage(page - 1);
  }, [empty, page]);

  const refresh = (recipeCaches: boolean) => {
    void queryClient.invalidateQueries({ queryKey: ['admin', 'reports'] });
    void queryClient.invalidateQueries({ queryKey: ['admin', 'reportCount'] });
    if (recipeCaches) {
      void queryClient.invalidateQueries({ queryKey: ['recipes'] });
      void queryClient.invalidateQueries({ queryKey: ['recipe'] });
    }
  };

  const act = useMutation({
    mutationFn: ({ type, item, reason }: Action & { reason?: string }) => {
      if (type === 'dismiss') return item.kind === 'recipe' ? dismissRecipeReports(item.id) : dismissCommentReports(item.id);
      return item.kind === 'recipe' ? removeRecipe(item.id, reason!) : removeComment(item.id, reason!);
    },
    onSuccess: (_data, { type, item }) => {
      setAction(null);
      setHandled(false);
      toast.success(type === 'dismiss' ? 'Reports dismissed' : item.kind === 'recipe' ? 'Recipe removed' : 'Comment removed');
      refresh(true);
    },
    onError: (e) => {
      if (isAlreadyHandled(e)) {
        setAction(null);
        setHandled(true);
        refresh(false);
      } else if (isAdminMfaSetupRequired(e)) {
        toast.error(MFA_SETUP_MESSAGE);
      } else if (e instanceof ApiError && e.errors?.reason) {
        setReasonError(e.errors.reason);
      } else {
        toast.error(e instanceof ApiError ? e.detail : "Couldn't complete that");
      }
    },
  });

  if (query.isError && isAdminMfaSetupRequired(query.error)) return <AdminMfaPanel />;
  if (query.isPending) return <Spinner />;
  if (query.isError && !query.data) return <EmptyState icon={CircleAlert}>Couldn't load the reports.</EmptyState>;

  const { content, page: paging } = query.data;
  const dismissing = action?.type === 'dismiss' ? action.item : null;
  const removing = action?.type === 'remove' ? action.item : null;

  return (
    <div className="mx-auto max-w-3xl">
      <h1 className="font-serif text-[32px] font-semibold tracking-[-0.015em] text-ink">Reports</h1>
      {handled && (
        <p role="alert" className="mt-4 text-[15px] font-medium text-danger">{ALREADY_HANDLED}</p>
      )}
      {content.length === 0 ? (
        <EmptyState icon={Flag}>No open reports.</EmptyState>
      ) : (
        <div className="mt-6 space-y-4">
          {content.map((item) => (
            <ReportEntry
              key={itemKey(item)}
              item={item}
              onDismiss={() => { setHandled(false); setAction({ type: 'dismiss', item }); }}
              onRemove={() => { setHandled(false); setReasonError(null); setAction({ type: 'remove', item }); }}
            />
          ))}
        </div>
      )}
      <Pagination page={paging.number} totalPages={paging.totalPages} onPage={(p) => { setHandled(false); setPage(p); }} />

      <ConfirmDialog
        open={dismissing !== null}
        title="Dismiss these reports?"
        description="The item will show again."
        confirmLabel="Dismiss"
        busy={act.isPending}
        onConfirm={() => { if (action && !act.isPending) act.mutate(action); }}
        onCancel={() => setAction(null)}
      />
      <ReasonDialog
        open={removing !== null}
        title={removing?.kind === 'comment' ? 'Remove this comment?' : 'Remove this recipe?'}
        description="The author will get an email with your reason."
        confirmLabel={removing?.kind === 'comment' ? 'Remove comment' : 'Remove recipe'}
        busy={act.isPending}
        error={reasonError}
        onCancel={() => setAction(null)}
        onEdit={() => setReasonError(null)}
        onSubmit={(reason) => {
          if (!action) return;
          setReasonError(null);
          act.mutate({ ...action, reason });
        }}
      />
    </div>
  );
}
