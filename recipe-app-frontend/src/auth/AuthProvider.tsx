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

    async function resolveProfile(s: Session | null) {
      if (!s) {
        if (!cancelled) { setProfileState(null); setStatus('anonymous'); }
        return;
      }
      try {
        const me = await getMe();
        if (!cancelled) { setProfileState(me); setStatus('ready'); }
      } catch (e) {
        if (!cancelled && e instanceof ApiError && e.status === 404) {
          setProfileState(null);
          setStatus('needs-profile');
        } else if (!cancelled) {
          setProfileState(null);
          setStatus('anonymous'); // e.g. 401 already signed us out
        }
      }
    }

    supabase.auth.getSession().then(({ data }) => {
      if (cancelled) return;
      setSession(data.session);
      void resolveProfile(data.session);
    });
    const { data: { subscription } } = supabase.auth.onAuthStateChange((_event, s) => {
      setSession(s);
      setStatus('loading');
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
