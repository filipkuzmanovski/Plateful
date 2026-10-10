import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { supabase } from '@/auth/supabaseClient';
import { MailCheck } from 'lucide-react';
import { Button, Field, TextInput } from '@/components/ui';
import { AuthCard, authLinkClass, FormError } from './AuthCard';

export default function SignupPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmEmail, setConfirmEmail] = useState(false);
  const navigate = useNavigate();

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const { data, error } = await supabase.auth.signUp({ email, password });
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
      {confirmEmail ? (
        <p role="status" className="flex items-start gap-3 rounded-2xl bg-saffron/15 px-4 py-3.5 text-sm text-olive">
          <MailCheck aria-hidden="true" className="mt-0.5 size-4.5 shrink-0 text-[#7a4f16]" />
          Check your email to confirm your account, then sign in.
        </p>
      ) : (
      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="Email">
          <TextInput type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
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
