import { useId, useState, type FormEvent } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { Button } from '@/components/ui';

export const MAX_REASON = 500;

function ReasonForm({
  confirmLabel,
  busy,
  error,
  onCancel,
  onEdit,
  onSubmit,
}: {
  confirmLabel: string;
  busy: boolean;
  error: string | null;
  onCancel: () => void;
  onEdit: () => void;
  onSubmit: (reason: string) => void;
}) {
  const fieldId = useId();
  const countId = `${fieldId}-count`;
  const [reason, setReason] = useState('');
  const length = reason.trim().length;
  const valid = length >= 1 && length <= MAX_REASON;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (valid && !busy) onSubmit(reason.trim());
  };

  return (
    <form onSubmit={submit} className="mt-4">
      <label htmlFor={fieldId} className="mb-1.5 block text-[15px] font-semibold text-ink">
        Reason
      </label>
      <textarea
        id={fieldId}
        value={reason}
        onChange={(e) => { setReason(e.target.value); onEdit(); }}
        rows={5}
        required
        aria-describedby={countId}
        className="w-full resize-y rounded-lg border border-field bg-surface px-3.5 py-2.5 text-base text-ink transition placeholder:text-ink-soft hover:border-ink-soft focus:border-ink focus:ring-4 focus:ring-ink/15 focus:outline-none sm:text-[15px]"
      />
      <div className="mt-1.5 flex items-start justify-between gap-3">
        {error ? <span role="alert" className="text-[13px] font-medium text-danger">{error}</span> : <span />}
        <span
          id={countId}
          className={`text-[13px] tabular-nums ${length > MAX_REASON ? 'font-semibold text-danger' : 'text-ink-soft'}`}
        >
          {length}/{MAX_REASON}
        </span>
      </div>
      <div className="mt-6 flex flex-wrap justify-end gap-2">
        <Button type="button" variant="outline" disabled={busy} onClick={onCancel}>Cancel</Button>
        <Button type="submit" variant="danger" disabled={!valid || busy}>{confirmLabel}</Button>
      </div>
    </form>
  );
}

/** Asks the admin for the reason behind a decision (reject, remove). The reason is required and counted on its trimmed length (1 to 500). */
export function ReasonDialog({
  title,
  description,
  confirmLabel,
  open,
  busy,
  error,
  onCancel,
  onEdit,
  onSubmit,
}: {
  title: string;
  description: string;
  confirmLabel: string;
  open: boolean;
  busy: boolean;
  /** A reason problem reported by the server, shown under the field. */
  error: string | null;
  onCancel: () => void;
  /** The reason text changed, so a server complaint about the old text no longer applies. */
  onEdit: () => void;
  onSubmit: (reason: string) => void;
}) {
  return (
    // Escape and overlay clicks are ignored mid-request, so the dialog cannot vanish before the outcome is known.
    <Dialog.Root open={open} onOpenChange={(o) => !o && !busy && onCancel()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 animate-overlay-in bg-scrim" />
        <Dialog.Content className="fixed top-1/2 left-1/2 z-50 w-[min(30rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 animate-dialog-in rounded-card border border-rule bg-surface p-6 shadow-float sm:p-7">
          <Dialog.Title className="font-serif text-[22px] font-semibold tracking-[-0.015em] text-ink">{title}</Dialog.Title>
          <Dialog.Description className="mt-2 text-[15px] leading-relaxed text-ink-soft">
            {description}
          </Dialog.Description>
          {/* Mounted only while open, so every opening starts with an empty reason. */}
          <ReasonForm confirmLabel={confirmLabel} busy={busy} error={error} onCancel={onCancel} onEdit={onEdit} onSubmit={onSubmit} />
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
