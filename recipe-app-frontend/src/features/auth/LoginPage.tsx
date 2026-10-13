import { useEffect, useState, type FormEvent } from 'react';
import { Clock } from 'lucide-react';
import { Link, useLocation, useNavigate } from 'react-router';
import { supabase } from '@/auth/supabaseClient';
import { SIGNED_OUT_IDLE_KEY } from '@/auth/idleTimeout';
import { Button, Field, TextInput } from '@/components/ui';
import { AuthCard, authLinkClass, FormError } from './AuthCard';

export default function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  // Read on mount, removed in an effect (not in the initializer, which StrictMode runs twice): shown once.
  const [signedOutIdle] = useState(() => {
    try { return sessionStorage.getItem(SIGNED_OUT_IDLE_KEY) === '1'; } catch { return false; }
  });
  useEffect(() => {
    try { sessionStorage.removeItem(SIGNED_OUT_IDLE_KEY); } catch { /* storage unavailable */ }
  }, []);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const { error } = await supabase.auth.signInWithPassword({ email, password });
    if (error) {
      setBusy(false);
      setError(error.message);
      return;
    }
    const from = (location.state as { from?: string } | null)?.from ?? '/';
    // Half signed-in? An account with a verified factor still has to enter its code.
    let needsCode = false;
    try {
      const { data } = await supabase.auth.mfa.getAuthenticatorAssuranceLevel();
      needsCode = data?.currentLevel === 'aal1' && data?.nextLevel === 'aal2';
    } catch {
      // fall through to the normal path; the app and the backend still enforce aal2
    }
    setBusy(false);
    if (needsCode) navigate('/verify-2fa', { state: { from } });
    else navigate(from);
  }

  return (
    <AuthCard
      title="Sign in"
      subtitle="Welcome back. Your saved ratings and recipes are waiting."
      footer={<>No account? <Link to="/signup" className={authLinkClass}>Sign up</Link></>}
    >
      {signedOutIdle && (
        <p role="status" className="mb-4 flex items-start gap-3 rounded-2xl bg-saffron/15 px-4 py-3.5 text-sm text-olive">
          <Clock aria-hidden="true" className="mt-0.5 size-4.5 shrink-0 text-saffron-deep" />
          You were signed out after 60 minutes of inactivity.
        </p>
      )}
      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="Email">
          <TextInput type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </Field>
        <Field label="Password">
          <TextInput type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </Field>
        {error && <FormError>{error}</FormError>}
        <Button type="submit" disabled={busy} className="mt-2 w-full">Sign in</Button>
      </form>
    </AuthCard>
  );
}
