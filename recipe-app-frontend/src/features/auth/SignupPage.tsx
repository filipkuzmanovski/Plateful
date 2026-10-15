import { useEffect, useId, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { supabase } from '@/auth/supabaseClient';
import { MailCheck, UserCheck } from 'lucide-react';
import { Button, Field, TextInput } from '@/components/ui';
import { AuthCard, authLinkClass, FormError } from './AuthCard';
import { ageOn, MIN_AGE, validateBirthDate } from './ageCheck';

// Older versions locked an under-13 visitor out for the tab via this key. There is no lockout now;
// the key is only removed on mount so anyone who was already blocked is freed.
const LEGACY_BLOCK_KEY = 'plateful.ageBlocked';

function clearLegacyBlock() {
  try {
    sessionStorage.removeItem(LEGACY_BLOCK_KEY);
  } catch {
    /* storage unavailable */
  }
}

const UNDER_AGE_MESSAGE = 'Sorry, you need to be at least 13 to create a Plateful account.';

export default function SignupPage() {
  const [email, setEmail] = useState('');
  const [birth, setBirth] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmEmail, setConfirmEmail] = useState(false);
  const [existingAccount, setExistingAccount] = useState(false);
  const [birthError, setBirthError] = useState<string | null>(null);
  const birthErrorId = useId();
  const navigate = useNavigate();

  useEffect(clearLegacyBlock, []);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBirthError(null);
    const today = new Date();
    if (validateBirthDate(birth, today) !== 'ok') {
      setError('Please enter a valid date of birth');
      return;
    }
    if ((ageOn(birth, today) ?? 0) < MIN_AGE) {
      setBirthError(UNDER_AGE_MESSAGE);
      return;
    }
    setBusy(true);
    // Only the fact that the age check passed is sent, never the birth date.
    const { data, error } = await supabase.auth.signUp({
      email,
      password,
      options: { data: { age_confirmed_at: today.toISOString() } },
    });
    setBusy(false);
    if (error) setError(error.message);
    // With email confirmation on, Supabase hides duplicates by returning a user with no identities.
    else if (data.user?.identities?.length === 0) setExistingAccount(true);
    else if (data.session) navigate('/complete-profile'); // provisioning happens there
    else setConfirmEmail(true); // email confirmation is on: no session until they confirm
  }

  return (
    <AuthCard
      title="Create account"
      subtitle="Join Plateful to share your recipes, rate dishes and join the conversation."
      footer={<>Have an account? <Link to="/login" className={authLinkClass}>Sign in</Link></>}
    >
      {existingAccount ? (
        <p role="status" className="flex items-start gap-3 rounded-2xl bg-saffron/15 px-4 py-3.5 text-sm text-olive">
          <UserCheck aria-hidden="true" className="mt-0.5 size-4.5 shrink-0 text-saffron-deep" />
          <span>
            An account with this email already exists.{' '}
            <Link to="/login" className={authLinkClass}>Sign in</Link>
          </span>
        </p>
      ) : confirmEmail ? (
        <p role="status" className="flex items-start gap-3 rounded-2xl bg-saffron/15 px-4 py-3.5 text-sm text-olive">
          <MailCheck aria-hidden="true" className="mt-0.5 size-4.5 shrink-0 text-saffron-deep" />
          Check your email to confirm your account, then sign in.
        </p>
      ) : (
      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="Email">
          <TextInput type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </Field>
        <div>
          <Field label="Date of birth">
            <TextInput
              type="date"
              value={birth}
              onChange={(e) => {
                setBirth(e.target.value);
                setBirthError(null);
              }}
              required
              aria-invalid={birthError ? true : undefined}
              aria-describedby={birthError ? birthErrorId : undefined}
            />
          </Field>
          {birthError && (
            <p id={birthErrorId} role="alert" className="mt-1.5 text-xs font-medium text-danger">
              {birthError}
            </p>
          )}
        </div>
        <Field label="Password">
          <TextInput type="password" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={6} />
        </Field>
        {error && <FormError>{error}</FormError>}
        <Button type="submit" disabled={busy} className="mt-2 w-full">Sign up</Button>
      </form>
      )}
    </AuthCard>
  );
}
