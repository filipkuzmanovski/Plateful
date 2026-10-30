import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router';
import { savePreferences } from '@/api/preferences';
import { useAuth } from '@/auth/AuthProvider';
import { AuthCard } from '@/features/auth/AuthCard';
import { PreferencesForm } from './PreferencesForm';
import type { Preferences } from '@/lib/types';

/**
 * The sign-up step after the profile: collects allergies, diet and tastes before the first visit home.
 * A user who already has preferences is sent to the profile's Preferences tab instead.
 */
export default function PreferencesSetupPage() {
  const { status, profile, setProfile } = useAuth();
  const navigate = useNavigate();
  // Set as this page's own save makes the user 'ready', so that render goes home, not to the profile.
  const [justSaved, setJustSaved] = useState(false);

  if (status === 'ready' && !justSaved) return <Navigate to="/me?tab=preferences" replace />;

  async function onSave(p: Preferences) {
    await savePreferences(p);
    setJustSaved(true);
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
