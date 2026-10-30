import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { createMe } from '@/api/users';
import { useAuth } from '@/auth/AuthProvider';
import { ApiError } from '@/api/client';
import { Button, Field, TextInput } from '@/components/ui';
import { AuthCard, FormError } from '@/features/auth/AuthCard';

export default function CompleteProfilePage() {
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const { setProfile } = useAuth();
  const navigate = useNavigate();

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const profile = await createMe({ firstName, lastName });
      setProfile(profile);
      navigate('/preferences/setup');
    } catch (err) {
      setError(err instanceof ApiError ? err.detail : 'Something went wrong');
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthCard
      title="Complete your profile"
      subtitle="Tell us what to call you — shown next to your recipes and comments."
    >
      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="First name">
          <TextInput value={firstName} onChange={(e) => setFirstName(e.target.value)} required maxLength={255} />
        </Field>
        <Field label="Last name">
          <TextInput value={lastName} onChange={(e) => setLastName(e.target.value)} required maxLength={255} />
        </Field>
        {error && <FormError>{error}</FormError>}
        <Button type="submit" disabled={busy} className="mt-2 w-full">Save</Button>
      </form>
    </AuthCard>
  );
}
