import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ACTIVITY_THROTTLE_MS,
  IDLE_TIMEOUT_MS,
  LAST_ACTIVITY_KEY,
  SIGNED_OUT_IDLE_KEY,
  WARNING_MS,
} from './idleTimeout';

function readStoredActivity(): number {
  try {
    const n = Number(localStorage.getItem(LAST_ACTIVITY_KEY));
    return Number.isFinite(n) ? n : 0;
  } catch {
    return 0;
  }
}

/**
 * Tracks user activity while `enabled` and calls `onTimeout` once after IDLE_TIMEOUT_MS without any.
 * Idle time is always computed from a timestamp (the newer of memory and localStorage, so other
 * tabs count), never from a running counter, so a sleeping computer is caught on wake-up.
 * Returns the warning state: `secondsLeft` is null until the final WARNING_MS.
 */
export function useIdleTimeout(enabled: boolean, onTimeout: () => void) {
  const [secondsLeft, setSecondsLeft] = useState<number | null>(null);
  const onTimeoutRef = useRef(onTimeout);
  const recordRef = useRef<(force: boolean) => void>(() => {});

  useEffect(() => { onTimeoutRef.current = onTimeout; });

  useEffect(() => {
    if (!enabled) return;
    let last = 0; // newest activity timestamp seen by this tab
    let lastWrite = 0;
    let fired = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const lastActivity = () => Math.max(last, readStoredActivity());

    const check = () => {
      if (fired) return;
      clearTimeout(timer);
      const idle = Date.now() - lastActivity();
      if (idle >= IDLE_TIMEOUT_MS) {
        fired = true;
        setSecondsLeft(null);
        try { sessionStorage.setItem(SIGNED_OUT_IDLE_KEY, '1'); } catch { /* storage unavailable */ }
        onTimeoutRef.current();
        return;
      }
      const remaining = IDLE_TIMEOUT_MS - idle;
      if (remaining <= WARNING_MS) {
        setSecondsLeft(Math.ceil(remaining / 1000));
        timer = setTimeout(check, Math.min(1000, remaining));
      } else {
        setSecondsLeft(null);
        timer = setTimeout(check, remaining - WARNING_MS);
      }
    };

    const record = (force: boolean) => {
      if (fired) return;
      const now = Date.now();
      if (!force && now - lastWrite < ACTIVITY_THROTTLE_MS) return;
      lastWrite = now;
      last = now;
      try { localStorage.setItem(LAST_ACTIVITY_KEY, String(now)); } catch { /* storage unavailable */ }
      check();
    };
    recordRef.current = record;

    const onActivity = () => record(false);
    const onStorage = (e: StorageEvent) => {
      if (e.key !== LAST_ACTIVITY_KEY) return;
      const n = Number(e.newValue);
      if (Number.isFinite(n)) last = Math.max(last, n);
      check();
    };
    const onVisibility = () => { if (document.visibilityState === 'visible') check(); };

    const plain = ['pointerdown', 'keydown', 'touchstart', 'wheel'] as const;
    for (const t of plain) window.addEventListener(t, onActivity, { passive: true });
    window.addEventListener('scroll', onActivity, { capture: true, passive: true });
    window.addEventListener('storage', onStorage);
    window.addEventListener('focus', check);
    document.addEventListener('visibilitychange', onVisibility);

    // A new session starts now: an old timestamp from a previous session must not count.
    record(true);

    return () => {
      fired = true; // late callbacks from this session do nothing
      clearTimeout(timer);
      for (const t of plain) window.removeEventListener(t, onActivity);
      window.removeEventListener('scroll', onActivity, { capture: true });
      window.removeEventListener('storage', onStorage);
      window.removeEventListener('focus', check);
      document.removeEventListener('visibilitychange', onVisibility);
      recordRef.current = () => {};
      setSecondsLeft(null);
    };
  }, [enabled]);

  /** Counts as activity right now (the "Stay signed in" button). */
  const stay = useCallback(() => recordRef.current(true), []);
  return { secondsLeft: enabled ? secondsLeft : null, stay };
}
