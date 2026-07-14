import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { supabase } from '@/auth/supabaseClient';
import { Button, Field, TextInput } from '@/components/ui';

export default function SignupPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const { error } = await supabase.auth.signUp({ email, password });
    setBusy(false);
    if (error) setError(error.message);
    else navigate('/complete-profile'); // provisioning happens there
  }

  return (
    <div className="mx-auto max-w-sm py-16">
      <h1 className="mb-6 text-2xl font-extrabold tracking-tight">Create account</h1>
      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="Email">
          <TextInput type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </Field>
        <Field label="Password">
          <TextInput type="password" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={6} />
        </Field>
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        <Button type="submit" disabled={busy} className="w-full">Sign up</Button>
      </form>
      <p className="mt-4 text-sm text-muted">
        Have an account? <Link to="/login" className="text-accent underline">Sign in</Link>
      </p>
    </div>
  );
}
