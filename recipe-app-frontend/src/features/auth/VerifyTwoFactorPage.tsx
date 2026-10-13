import { useEffect, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { supabase } from '@/auth/supabaseClient';
import { useAuth } from '@/auth/AuthProvider';
import { Button, Field, Spinner, TextInput } from '@/components/ui';
import { AuthCard, FormError } from './AuthCard';

const WRONG_CODE = "That code didn't work. Check your app and try again.";

interface TotpFactor {
  id: string;
  friendly_name?: string;
}

type Loaded =
  | { kind: 'loading' }
  | { kind: 'none' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; factors: TotpFactor[] };

/** The second step of sign-in: the user is signed in at aal1 and proves the authenticator code to reach aal2. */
export default function VerifyTwoFactorPage() {
  const { status, signOut } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [factorId, setFactorId] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [attempt, setAttempt] = useState(0); // bumped by "Try again" to re-run the device-list load

  const needsMfa = status === 'needs-mfa';
  useEffect(() => {
    if (!needsMfa) return;
    let cancelled = false;
    supabase.auth.mfa
      .listFactors()
      .then(({ data, error: listError }) => {
        if (cancelled) return;
        if (listError) {
          setLoaded({ kind: 'error', message: listError.message });
          return;
        }
        const factors = (data?.totp ?? []) as TotpFactor[];
        if (factors.length === 0) {
          setLoaded({ kind: 'none' });
          return;
        }
        setFactorId(factors[0].id);
        setLoaded({ kind: 'ready', factors });
      })
      .catch((e: unknown) => {
        if (!cancelled) setLoaded({ kind: 'error', message: e instanceof Error ? e.message : 'Something went wrong.' });
      });
    return () => { cancelled = true; };
  }, [needsMfa, attempt]);

  if (status === 'loading') return <Spinner />;
  if (status === 'anonymous') return <Navigate to="/login" replace />;
  if (status === 'ready') return <Navigate to={from} replace />;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const { data: challenge, error: challengeError } = await supabase.auth.mfa.challenge({ factorId });
      if (challengeError || !challenge) throw challengeError ?? new Error('Could not start verification.');
      const { error: verifyError } = await supabase.auth.mfa.verify({ factorId, challengeId: challenge.id, code });
      if (verifyError) throw verifyError;
      navigate(from, { replace: true });
    } catch (err) {
      const { status: errStatus, code: errCode, message } = err as { status?: number; code?: string; message?: string };
      if (errCode === 'mfa_verification_failed' || errStatus === 422 || /code/i.test(message ?? '')) setError(WRONG_CODE);
      else setError(message || 'Something went wrong. Please try again.');
      setCode('');
    } finally {
      setBusy(false);
    }
  }

  const signOutButton = (
    <Button type="button" variant="ghost" onClick={() => void signOut()} className="w-full">
      Sign out
    </Button>
  );

  return (
    <AuthCard title="Two-factor verification" subtitle="Enter the 6-digit code from your authenticator app.">
      {loaded.kind === 'loading' && <Spinner />}
      {loaded.kind === 'error' && (
        <div className="space-y-4">
          <FormError>{loaded.message}</FormError>
          <Button
            type="button"
            variant="outline"
            onClick={() => { setLoaded({ kind: 'loading' }); setAttempt((n) => n + 1); }}
            className="w-full"
          >
            Try again
          </Button>
          {signOutButton}
        </div>
      )}
      {loaded.kind === 'none' && (
        <div className="space-y-4">
          <p className="text-sm text-olive/70">No authenticator found for this account.</p>
          {signOutButton}
        </div>
      )}
      {loaded.kind === 'ready' && (
        <form onSubmit={onSubmit} className="space-y-4">
          {loaded.factors.length > 1 && (
            <fieldset className="space-y-2">
              <legend className="mb-1.5 text-[13px] font-semibold text-olive">Which device?</legend>
              {loaded.factors.map((f) => (
                <label key={f.id} className="flex items-center gap-2.5 text-sm text-olive">
                  <input
                    type="radio"
                    name="factor"
                    value={f.id}
                    checked={factorId === f.id}
                    onChange={() => setFactorId(f.id)}
                    className="size-4 accent-olive"
                  />
                  {f.friendly_name || 'Authenticator'}
                </label>
              ))}
            </fieldset>
          )}
          <Field label="Code">
            <TextInput
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              pattern="\d{6}"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
              required
              autoFocus
            />
          </Field>
          {error && <FormError>{error}</FormError>}
          <div className="space-y-2 pt-2">
            <Button type="submit" disabled={busy} className="w-full">Verify</Button>
            {signOutButton}
          </div>
        </form>
      )}
    </AuthCard>
  );
}
