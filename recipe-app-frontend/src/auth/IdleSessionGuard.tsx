import { useRef } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { useNavigate } from 'react-router';
import { Button } from '@/components/ui';
import { useAuth } from './AuthProvider';
import { IDLE_WARNING_ATTRIBUTE, useIdleTimeout } from './useIdleTimeout';

/** Signs the user out after 60 minutes without activity, with a one-minute warning first. */
export function IdleSessionGuard() {
  const { status, signOut } = useAuth();
  const navigate = useNavigate();
  const enabled = status === 'ready' || status === 'needs-mfa' || status === 'needs-profile' || status === 'needs-preferences';

  const timeOut = () => {
    // A failed sign-out must still send the user to the login page.
    void signOut().catch(() => undefined).finally(() => navigate('/login'));
  };
  const { secondsLeft, stay } = useIdleTimeout(enabled, timeOut);
  const contentRef = useRef<HTMLDivElement>(null);

  // A deliberate sign-out is not an inactivity timeout: it does not set the idle flag, so the
  // login page shows no "signed out after 60 minutes" message.
  const signOutNow = () => timeOut();

  const n = secondsLeft ?? 0;
  return (
    <Dialog.Root open={secondsLeft !== null} onOpenChange={(o) => { if (!o) stay(); }}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 animate-overlay-in bg-scrim" />
        <Dialog.Content
          {...{ [IDLE_WARNING_ATTRIBUTE]: '' }}
          ref={contentRef}
          // Radix would focus the first button ("Sign out now"); the safe default is "Stay signed in".
          onOpenAutoFocus={(e) => {
            e.preventDefault();
            contentRef.current?.querySelector<HTMLElement>('[data-stay-signed-in]')?.focus();
          }}
          className="fixed top-1/2 left-1/2 z-50 w-[min(26rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 animate-dialog-in rounded-card border border-rule bg-surface p-6 shadow-float sm:p-7">
          <Dialog.Title className="font-serif text-[22px] font-semibold tracking-[-0.015em] text-ink">Still there?</Dialog.Title>
          <Dialog.Description className="mt-2 text-[15px] leading-relaxed text-ink-soft">
            {`You'll be signed out in ${n} ${n === 1 ? 'second' : 'seconds'} due to inactivity.`}
          </Dialog.Description>
          <div className="mt-6 flex flex-wrap justify-end gap-2">
            <Button variant="outline" onClick={signOutNow}>Sign out now</Button>
            <Button data-stay-signed-in onClick={stay}>Stay signed in</Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
