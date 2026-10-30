import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ApiError } from '@/api/client';
import { getPreferences, preferencesQueryKey, savePreferences } from '@/api/preferences';
import { useAuth } from '@/auth/AuthProvider';
import { Button, Spinner } from '@/components/ui';
import { PreferencesForm } from './PreferencesForm';
import type { Preferences } from '@/lib/types';

/** The server answers 404 until preferences are first saved; that is "nothing saved yet", not a failure. */
async function loadPreferences(): Promise<Preferences | null> {
  try {
    return await getPreferences();
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) return null;
    throw err;
  }
}

/** The profile's "Preferences" tab: the signed-in user's saved food preferences, editable. */
export function PreferencesPanel() {
  const { profile } = useAuth();
  const queryClient = useQueryClient();
  const userId = profile?.id ?? '';
  // Bumped after each save so the form remounts from what the server stored (it may drop or normalise names).
  const [savedVersion, setSavedVersion] = useState(0);
  const preferences = useQuery({
    queryKey: preferencesQueryKey(userId),
    queryFn: loadPreferences,
    enabled: !!profile,
  });

  async function onSave(p: Preferences) {
    const saved = await savePreferences(p);
    queryClient.setQueryData(preferencesQueryKey(userId), saved);
    setSavedVersion((v) => v + 1);
    toast.success('Preferences saved');
  }

  if (preferences.isPending) return <Spinner />;
  if (preferences.isError) {
    return (
      <div className="flex flex-wrap items-center gap-3 text-sm text-danger">
        <span>Couldn't load your preferences.</span>
        <Button type="button" variant="outline" className="!px-3.5 !py-1" onClick={() => void preferences.refetch()}>
          Retry
        </Button>
      </div>
    );
  }

  return (
    <section className="max-w-3xl rounded-card bg-paper p-5 shadow-card ring-1 ring-sand/70 sm:p-7">
      <p className="mb-6 text-sm text-olive/70">
        We use these to suggest recipes. Allergies and ingredients you avoid are always filtered out.
      </p>
      <PreferencesForm
        key={savedVersion}
        initial={preferences.data ?? undefined}
        submitLabel="Save preferences"
        onSave={onSave}
      />
    </section>
  );
}
