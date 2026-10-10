import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import type { Session } from '@supabase/supabase-js';
import { supabase } from './supabaseClient';
import { getMe } from '@/api/users';
import { ApiError } from '@/api/client';
import type { UserDisplay } from '@/lib/types';

type Status = 'loading' | 'anonymous' | 'needs-profile' | 'ready';

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

    async function resolveProfile(s: Session | null) {
      const key = s?.user?.id ?? null;
      if (key === lastUserKey) return; // dedupes INITIAL_SESSION/getSession double-fire and token refreshes
      lastUserKey = key;
      const run = ++latest;
      const isCurrent = () => !cancelled && run === latest;

      if (!s) {
        if (isCurrent()) { setProfileState(null); setStatus('anonymous'); }
        return;
      }
      if (isCurrent()) setStatus('loading');
      try {
        const me = await getMe();
        if (isCurrent()) { setProfileState(me); setStatus('ready'); }
      } catch (e) {
        if (!isCurrent()) return; // a newer session already took over
        setProfileState(null);
        if (e instanceof ApiError && e.status === 404) {
          setStatus('needs-profile');
        } else {
          lastUserKey = undefined; // transient failure: let the next auth event retry
          setStatus('anonymous'); // e.g. 401 already signed us out
        }
      }
    }

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
    return () => { cancelled = true; subscription.unsubscribe(); };
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
