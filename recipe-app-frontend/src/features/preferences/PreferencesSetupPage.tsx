import { useNavigate } from 'react-router';
import { savePreferences } from '@/api/preferences';
import { useAuth } from '@/auth/AuthProvider';
import { AuthCard } from '@/features/auth/AuthCard';
import { PreferencesForm } from './PreferencesForm';
import type { Preferences } from '@/lib/types';

/** The sign-up step after the profile: collects allergies, diet and tastes before the first visit home. */
export default function PreferencesSetupPage() {
  const { profile, setProfile } = useAuth();
  const navigate = useNavigate();

  async function onSave(p: Preferences) {
    await savePreferences(p);
    if (profile) setProfile({ ...profile, hasPreferences: true });
    navigate('/', { replace: true });
  }

  return (
    <AuthCard
      wide
      title="Your food preferences"
      subtitle="We use these to suggest recipes. Allergies and ingredients you avoid are always filtered out."
    >
      <PreferencesForm submitLabel="Save preferences" onSave={onSave} />
    </AuthCard>
  );
}
