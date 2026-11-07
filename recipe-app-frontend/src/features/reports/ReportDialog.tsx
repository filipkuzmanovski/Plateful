import { useId, useState, type FormEvent } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { Button } from '@/components/ui';
import type { ReportReason } from '@/api/reports';

const MAX_NOTE = 500;

const REASONS: { value: ReportReason; label: string }[] = [
  { value: 'spam', label: 'Spam' },
  { value: 'offensive', label: 'Offensive or hateful' },
  { value: 'unsafe', label: 'Unsafe food advice' },
  { value: 'not_food', label: 'Not food related' },
  { value: 'other', label: 'Other' },
];

function ReportForm({
  busy,
  error,
  onCancel,
  onSubmit,
}: {
  busy: boolean;
  error: string | null;
  onCancel: () => void;
  onSubmit: (reason: ReportReason, note: string | null) => void;
}) {
  const fieldId = useId();
  const countId = `${fieldId}-count`;
  const [reason, setReason] = useState<ReportReason | null>(null);
  const [note, setNote] = useState('');

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (reason && !busy) onSubmit(reason, note.trim() || null);
  };

  return (
    <form onSubmit={submit} className="mt-4">
      <fieldset className="space-y-1">
        <legend className="mb-1.5 text-[15px] font-semibold text-ink">Reason</legend>
        {REASONS.map((r) => (
          <label key={r.value} className="flex min-h-11 cursor-pointer items-center gap-3 rounded-lg px-2 text-[15px] text-ink hover:bg-tint">
            <input
              type="radio"
              name="report-reason"
              value={r.value}
              checked={reason === r.value}
              onChange={() => setReason(r.value)}
              className="size-4 accent-ink"
            />
            {r.label}
          </label>
        ))}
      </fieldset>
      <label htmlFor={fieldId} className="mt-4 mb-1.5 block text-[15px] font-semibold text-ink">
        Note (optional)
      </label>
      <textarea
        id={fieldId}
        value={note}
        onChange={(e) => setNote(e.target.value)}
        maxLength={MAX_NOTE}
        rows={4}
        aria-describedby={countId}
        className="w-full resize-y rounded-lg border border-field bg-surface px-3.5 py-2.5 text-base text-ink transition placeholder:text-ink-soft hover:border-ink-soft focus:border-ink focus:ring-4 focus:ring-ink/15 focus:outline-none sm:text-[15px]"
      />
      <div className="mt-1.5 flex items-start justify-between gap-3">
        {error ? <span role="alert" className="text-[13px] font-medium text-danger">{error}</span> : <span />}
        <span id={countId} className="text-[13px] tabular-nums text-ink-soft">
          {note.length}/{MAX_NOTE}
        </span>
      </div>
      <div className="mt-6 flex flex-wrap justify-end gap-2">
        <Button type="button" variant="outline" disabled={busy} onClick={onCancel}>Cancel</Button>
        <Button type="submit" variant="danger" disabled={!reason || busy}>Send report</Button>
      </div>
    </form>
  );
}

/** Asks why a recipe or comment is being reported. After a send (or a duplicate) the body becomes a short message. */
export function ReportDialog({
  title,
  open,
  busy,
  error,
  done,
  onClose,
  onSubmit,
}: {
  title: string;
  open: boolean;
  busy: boolean;
  /** The server's complaint, shown under the note. */
  error: string | null;
  /** A message that replaces the form (thanks, or already reported). */
  done: string | null;
  onClose: () => void;
  onSubmit: (reason: ReportReason, note: string | null) => void;
}) {
  return (
    // Escape and overlay clicks are ignored mid-request, so the dialog cannot vanish before the outcome is known.
    <Dialog.Root open={open} onOpenChange={(o) => !o && !busy && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 animate-overlay-in bg-scrim" />
        <Dialog.Content className="fixed top-1/2 left-1/2 z-50 max-h-[calc(100vh-2rem)] w-[min(30rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 animate-dialog-in overflow-y-auto rounded-card border border-rule bg-surface p-6 shadow-float sm:p-7">
          <Dialog.Title className="font-serif text-[22px] font-semibold tracking-[-0.015em] text-ink">{title}</Dialog.Title>
          <Dialog.Description className="mt-2 text-[15px] leading-relaxed text-ink-soft">
            {done ?? 'An admin will review it. Pick the closest reason.'}
          </Dialog.Description>
          {done ? (
            <div className="mt-6 flex justify-end">
              <Button type="button" variant="outline" onClick={onClose}>Close</Button>
            </div>
          ) : (
            // Mounted only while open and unsent, so every opening starts empty.
            <ReportForm busy={busy} error={error} onCancel={onClose} onSubmit={onSubmit} />
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
