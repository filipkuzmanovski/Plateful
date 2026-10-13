import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { supabase } from '@/auth/supabaseClient';
import { MailCheck, ShieldAlert } from 'lucide-react';
import { Button, Field, TextInput } from '@/components/ui';
import { AuthCard, authLinkClass, FormError } from './AuthCard';
import { ageOn, MIN_AGE, validateBirthDate } from './ageCheck';

const BLOCK_KEY = 'plateful.ageBlocked';

// sessionStorage can throw (privacy modes, disabled storage); the in-memory
// `blocked` state still applies when it does.
function readBlocked(): boolean {
  try {
    return sessionStorage.getItem(BLOCK_KEY) === '1';
  } catch {
    return false;
  }
}

function writeBlocked() {
  try {
    sessionStorage.setItem(BLOCK_KEY, '1');
  } catch {
    /* storage unavailable */
  }
}

export default function SignupPage() {
  const [email, setEmail] = useState('');
  const [birth, setBirth] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmEmail, setConfirmEmail] = useState(false);
  const [blocked, setBlocked] = useState(readBlocked);
  const navigate = useNavigate();

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const today = new Date();
    if (validateBirthDate(birth, today) !== 'ok') {
      setError('Please enter a valid date of birth');
      return;
    }
    if ((ageOn(birth, today) ?? 0) < MIN_AGE) {
      writeBlocked();
      setBlocked(true);
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
    else if (data.session) navigate('/complete-profile'); // provisioning happens there
    else setConfirmEmail(true); // email confirmation is on: no session until they confirm
  }

  return (
    <AuthCard
      title="Create account"
      subtitle="Join Plateful to share your recipes, rate dishes and join the conversation."
      footer={<>Have an account? <Link to="/login" className={authLinkClass}>Sign in</Link></>}
    >
      {blocked ? (
        <p role="status" className="flex items-start gap-3 rounded-2xl bg-saffron/15 px-4 py-3.5 text-sm text-olive">
          <ShieldAlert aria-hidden="true" className="mt-0.5 size-4.5 shrink-0 text-saffron-deep" />
          Sorry, you need to be at least 13 to create a Plateful account.
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
        <Field label="Date of birth">
          <TextInput type="date" value={birth} onChange={(e) => setBirth(e.target.value)} required />
        </Field>
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
