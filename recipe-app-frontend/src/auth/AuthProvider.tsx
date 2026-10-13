import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import type { Session } from '@supabase/supabase-js';
import { supabase } from './supabaseClient';
import { getMe } from '@/api/users';
import { ApiError, setMfaRequiredHandler } from '@/api/client';
import { aalOf } from './aal';
import type { UserDisplay } from '@/lib/types';

type Status = 'loading' | 'anonymous' | 'needs-profile' | 'needs-mfa' | 'ready';

interface AuthState {
  session: Session | null;
  profile: UserDisplay | null;
  status: Status;
  signOut: () => Promise<void>;
  setProfile: (p: UserDisplay) => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [profile, setProfileState] = useState<UserDisplay | null>(null);
  const [status, setStatus] = useState<Status>('loading');

  useEffect(() => {
    let cancelled = false;
    let lastUserKey: string | null | undefined; // undefined = nothing resolved yet
    let latest = 0; // id of the newest resolution; results from older ones are dropped
    let readyUserId: string | null = null; // user whose profile is loaded; same-user re-resolves stay 'ready'

    async function resolveProfile(s: Session | null) {
      // The assurance level is part of the key: an aal1 -> aal2 upgrade is a real change to resolve.
      const key = s ? `${s.user.id}:${aalOf(s) ?? 'none'}` : null;
      if (key === lastUserKey) return; // dedupes INITIAL_SESSION/getSession double-fire and token refreshes
      lastUserKey = key;
      const run = ++latest;
      const isCurrent = () => !cancelled && run === latest;

      if (!s) {
        readyUserId = null;
        if (isCurrent()) { setProfileState(null); setStatus('anonymous'); }
        return;
      }
      // An aal1 -> aal2 upgrade of an already-loaded user refetches in the background; showing the
      // spinner would unmount protected pages mid-flow. A different user or a first load still shows it.
      if (readyUserId !== s.user.id) {
        readyUserId = null;
        if (isCurrent()) setStatus('loading');
      }
      if (aalOf(s) === 'aal1') {
        // Half signed-in? An account with a verified factor must reach aal2 before /me can load.
        try {
          const { data } = await supabase.auth.mfa.getAuthenticatorAssuranceLevel();
          if (data && data.currentLevel === 'aal1' && data.nextLevel === 'aal2') {
            readyUserId = null;
            if (isCurrent()) { setProfileState(null); setStatus('needs-mfa'); }
            return;
          }
        } catch {
          // fall back to the normal path; the backend still enforces aal2
        }
        if (!isCurrent()) return;
      }
      try {
        const me = await getMe();
        if (isCurrent()) { readyUserId = s.user.id; setProfileState(me); setStatus('ready'); }
      } catch (e) {
        if (!isCurrent()) return; // a newer session already took over
        readyUserId = null;
        setProfileState(null);
        if (e instanceof ApiError && e.status === 404) {
          setStatus('needs-profile');
        } else if (e instanceof ApiError && e.code === 'mfa_required') {
          // The API wants the second factor (e.g. enabled on another device since this tab loaded).
          // Keep lastUserKey so duplicate events don't loop; the aal2 upgrade changes the key.
          setStatus('needs-mfa');
        } else {
          lastUserKey = undefined; // transient failure: let the next auth event retry
          setStatus('anonymous'); // e.g. 401 already signed us out
        }
      }
    }

    // Only a current session that is below aal2 can legitimately need the second factor. A late answer
    // after sign-out, or after the aal2 upgrade, must not strand the app in needs-mfa.
    setMfaRequiredHandler(() => {
      if (!cancelled && typeof lastUserKey === 'string' && !lastUserKey.endsWith(':aal2')) {
        readyUserId = null;
        setStatus('needs-mfa');
      }
    });

    supabase.auth.getSession()
      .then(({ data }) => {
        if (cancelled) return;
        setSession(data.session);
        void resolveProfile(data.session);
      })
      .catch(() => {
        // A failed session read must not strand the app on the spinner — but if an auth
        // event already resolved a state, keep it.
        if (!cancelled && lastUserKey === undefined) {
          setSession(null);
          setProfileState(null);
          setStatus('anonymous');
        }
      });

    const { data: { subscription } } = supabase.auth.onAuthStateChange((_event, s) => {
      if (cancelled) return;
      setSession(s);
      void resolveProfile(s);
    });
    return () => {
      cancelled = true;
      setMfaRequiredHandler(null);
      subscription.unsubscribe();
    };
  }, []);

  const value: AuthState = {
    session,
    profile,
    status,
    signOut: async () => { await supabase.auth.signOut(); },
    setProfile: (p) => { setProfileState(p); setStatus('ready'); },
  };
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
