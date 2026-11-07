import { useState } from 'react';
import { Flag } from 'lucide-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ApiError } from '@/api/client';
import { reportContent, type ReportReason, type ReportTarget } from '@/api/reports';
import { ReportDialog } from './ReportDialog';

const THANKS = 'Thanks. An admin will take a look.';
const ALREADY = "You've already reported this.";

function problemFor(e: unknown): string | null {
  if (!(e instanceof ApiError)) return null;
  if (e.status === 429) return "You've sent 20 reports today. You can report more tomorrow.";
  if (e.status === 404) return 'This is no longer available.';
  return null;
}

/** "Report" for a recipe or comment; "Reported" (disabled) once the user has reported it. The caller decides when it may show. */
export function ReportButton({
  target,
  reported,
  variant,
  label,
  title,
}: {
  target: ReportTarget;
  /** The server already lists this item among the user's reports. */
  reported: boolean;
  variant: 'page' | 'inline';
  /** Accessible name suffix, e.g. "comment by Bob Smith". Omitted for the recipe. */
  label?: string;
  title: string;
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  const send = useMutation({
    mutationFn: ({ reason, note }: { reason: ReportReason; note: string | null }) =>
      reportContent(target, reason, note),
    onSuccess: () => {
      setSent(true);
      setDone(THANKS);
      void queryClient.invalidateQueries({ queryKey: ['myReports'] });
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        setSent(true);
        setDone(ALREADY);
        void queryClient.invalidateQueries({ queryKey: ['myReports'] });
        return;
      }
      const message = problemFor(e);
      if (message) setError(message);
      else {
        setOpen(false);
        toast.error(e instanceof ApiError ? e.detail : 'Something went wrong');
      }
    },
  });

  const base =
    variant === 'page'
      ? 'inline-flex min-h-11 items-center gap-2 rounded-full border border-field bg-surface px-4 text-[15px] font-semibold text-ink transition-colors hover:bg-tint disabled:pointer-events-none disabled:opacity-45'
      : 'inline-flex min-h-11 items-center gap-1 rounded-full px-3 text-[13px] font-medium text-ink-soft transition-colors hover:bg-tint hover:text-ink disabled:pointer-events-none disabled:opacity-45';
  const isReported = reported || sent;
  const word = isReported ? 'Reported' : 'Report';

  return (
    <>
      <button
        type="button"
        aria-label={label ? `${word} ${label}` : undefined}
        disabled={isReported}
        className={base}
        onClick={() => { setError(null); setOpen(true); }}
      >
        <Flag aria-hidden="true" className={variant === 'page' ? 'size-3.5' : 'size-3'} />
        {word}
      </button>
      <ReportDialog
        title={title}
        open={open}
        busy={send.isPending}
        error={error}
        done={done}
        onClose={() => setOpen(false)}
        onSubmit={(reason, note) => { setError(null); send.mutate({ reason, note }); }}
      />
    </>
  );
}
