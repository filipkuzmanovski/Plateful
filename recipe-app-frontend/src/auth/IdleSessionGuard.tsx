import * as Dialog from '@radix-ui/react-dialog';
import { useNavigate } from 'react-router';
import { Button } from '@/components/ui';
import { useAuth } from './AuthProvider';
import { SIGNED_OUT_IDLE_KEY } from './idleTimeout';
import { useIdleTimeout } from './useIdleTimeout';

/** Signs the user out after 60 minutes without activity, with a one-minute warning first. */
export function IdleSessionGuard() {
  const { status, signOut } = useAuth();
  const navigate = useNavigate();
  const enabled = status === 'ready' || status === 'needs-mfa' || status === 'needs-profile';

  const timeOut = () => {
    // A failed sign-out must still send the user to the login page.
    void signOut().catch(() => undefined).finally(() => navigate('/login'));
  };
  const { secondsLeft, stay } = useIdleTimeout(enabled, timeOut);

  const signOutNow = () => {
    try { sessionStorage.setItem(SIGNED_OUT_IDLE_KEY, '1'); } catch { /* storage unavailable */ }
    timeOut();
  };

  const n = secondsLeft ?? 0;
  return (
    <Dialog.Root open={secondsLeft !== null} onOpenChange={(o) => { if (!o) stay(); }}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 animate-overlay-in bg-olive/35 backdrop-blur-[2px]" />
        <Dialog.Content className="fixed top-1/2 left-1/2 z-50 w-[min(26rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 animate-dialog-in rounded-card bg-paper p-6 shadow-lift ring-1 ring-sand sm:p-7">
          <Dialog.Title className="font-serif text-2xl font-semibold tracking-tight text-olive">Still there?</Dialog.Title>
          <Dialog.Description className="mt-2 text-sm leading-relaxed text-olive/70">
            {`You'll be signed out in ${n} ${n === 1 ? 'second' : 'seconds'} due to inactivity.`}
          </Dialog.Description>
          <div className="mt-6 flex flex-wrap justify-end gap-2">
            <Button variant="outline" onClick={signOutNow}>Sign out now</Button>
            <Button onClick={stay}>Stay signed in</Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
