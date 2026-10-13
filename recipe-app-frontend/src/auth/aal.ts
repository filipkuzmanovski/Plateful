import type { Session } from '@supabase/supabase-js';

export type Aal = 'aal1' | 'aal2';

/**
 * The authenticator assurance level carried in the session's access token, or null when the
 * token is not a JWT or has no recognisable `aal` claim. Never throws.
 */
export function aalOf(session: Session | null): Aal | null {
  try {
    const payload = session?.access_token?.split('.')[1];
    if (!payload) return null;
    const b64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
    const claims: unknown = JSON.parse(atob(padded));
    const aal = (claims as { aal?: unknown } | null)?.aal;
    return aal === 'aal1' || aal === 'aal2' ? aal : null;
  } catch {
    return null;
  }
}
