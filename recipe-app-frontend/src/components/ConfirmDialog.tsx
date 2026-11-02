import * as Dialog from '@radix-ui/react-dialog';
import { Button } from './ui';

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = 'Confirm',
  busy = false,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: string;
  description: string;
  confirmLabel?: string;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={(o) => !o && onCancel()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 animate-overlay-in bg-scrim" />
        <Dialog.Content className="fixed top-1/2 left-1/2 z-50 w-[min(26rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 animate-dialog-in rounded-card border border-rule bg-surface p-6 shadow-float sm:p-7">
          <Dialog.Title className="font-serif text-[22px] font-semibold tracking-[-0.015em] text-ink">{title}</Dialog.Title>
          <Dialog.Description className="mt-2 text-[15px] leading-relaxed text-ink-soft">{description}</Dialog.Description>
          <div className="mt-6 flex flex-wrap justify-end gap-2">
            <Button variant="outline" onClick={onCancel}>Cancel</Button>
            <Button variant="danger" onClick={onConfirm} disabled={busy}>{confirmLabel}</Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
