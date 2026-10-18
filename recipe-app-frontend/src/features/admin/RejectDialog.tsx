import { useState, type FormEvent } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { Button } from '@/components/ui';

export const MAX_REASON = 500;

function RejectForm({
  busy,
  error,
  onCancel,
  onSubmit,
}: {
  busy: boolean;
  error: string | null;
  onCancel: () => void;
  onSubmit: (reason: string) => void;
}) {
  const [reason, setReason] = useState('');
  const length = reason.trim().length;
  const valid = length >= 1 && length <= MAX_REASON;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (valid && !busy) onSubmit(reason.trim());
  };

  return (
    <form onSubmit={submit} className="mt-4">
      <label htmlFor="reject-reason" className="mb-1.5 block text-[13px] font-semibold text-olive">
        Reason
      </label>
      <textarea
        id="reject-reason"
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        rows={5}
        required
        aria-describedby="reject-reason-count"
        className="w-full resize-y rounded-xl border border-sand bg-paper px-3.5 py-2.5 text-sm text-olive shadow-[inset_0_1px_2px_rgb(47_58_43/0.04)] transition placeholder:text-olive/70 hover:border-olive/30 focus:border-olive/60 focus:ring-4 focus:ring-saffron/20 focus:outline-none"
      />
      <div className="mt-1.5 flex items-start justify-between gap-3">
        {error ? <span role="alert" className="text-xs font-medium text-danger">{error}</span> : <span />}
        <span
          id="reject-reason-count"
          className={`text-xs tabular-nums ${length > MAX_REASON ? 'font-semibold text-danger' : 'text-olive/70'}`}
        >
          {length}/{MAX_REASON}
        </span>
      </div>
      <div className="mt-6 flex flex-wrap justify-end gap-2">
        <Button type="button" variant="outline" onClick={onCancel}>Cancel</Button>
        <Button type="submit" variant="danger" disabled={!valid || busy}>Reject recipe</Button>
      </div>
    </form>
  );
}

/** Asks the admin why a recipe is rejected. The reason is required and counted on its trimmed length (1 to 500). */
export function RejectDialog({
  open,
  busy,
  error,
  onCancel,
  onSubmit,
}: {
  open: boolean;
  busy: boolean;
  /** A reason problem reported by the server, shown under the field. */
  error: string | null;
  onCancel: () => void;
  onSubmit: (reason: string) => void;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={(o) => !o && onCancel()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 animate-overlay-in bg-olive/35 backdrop-blur-[2px]" />
        <Dialog.Content className="fixed top-1/2 left-1/2 z-50 w-[min(30rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 animate-dialog-in rounded-card bg-paper p-6 shadow-lift ring-1 ring-sand sm:p-7">
          <Dialog.Title className="font-serif text-2xl font-semibold tracking-tight text-olive">Reject recipe?</Dialog.Title>
          <Dialog.Description className="mt-2 text-sm leading-relaxed text-olive/70">
            The author will see this reason and can edit the recipe to send it back for review.
          </Dialog.Description>
          {/* Mounted only while open, so every opening starts with an empty reason. */}
          <RejectForm busy={busy} error={error} onCancel={onCancel} onSubmit={onSubmit} />
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
