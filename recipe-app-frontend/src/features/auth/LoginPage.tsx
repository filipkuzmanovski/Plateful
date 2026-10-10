import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { supabase } from '@/auth/supabaseClient';
import { Button, Field, TextInput } from '@/components/ui';
import { AuthCard, authLinkClass, FormError } from './AuthCard';

export default function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const { error } = await supabase.auth.signInWithPassword({ email, password });
    setBusy(false);
    if (error) setError(error.message);
    else navigate((location.state as { from?: string } | null)?.from ?? '/');
  }

  return (
    <AuthCard
      title="Sign in"
      subtitle="Welcome back. Your saved ratings and recipes are waiting."
      footer={<>No account? <Link to="/signup" className={authLinkClass}>Sign up</Link></>}
    >
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
