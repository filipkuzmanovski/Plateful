import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { updateMe, getUserRecipes } from '@/api/users';
import { useAuth } from '@/auth/AuthProvider';
import { ApiError } from '@/api/client';
import { RecipeCard } from '@/components/RecipeCard';
import { Pagination } from '@/components/Pagination';
import { Button, Field, SectionHeading, Spinner, TextInput } from '@/components/ui';
import { SavedRecipesPanel } from '@/features/saved/SavedRecipesPanel';
import { SavedAiRecipes } from '@/features/ai/SavedAiRecipes';
import { PreferencesPanel } from '@/features/preferences/PreferencesPanel';
import { ProfileHeader } from './ProfileHeader';
import { ProfileTabs } from './ProfileTabs';
import { TwoFactorSettings } from './TwoFactorSettings';

export default function MyProfilePage() {
  const { profile, setProfile } = useAuth();
  const [firstName, setFirstName] = useState(profile?.firstName ?? '');
  const [lastName, setLastName] = useState(profile?.lastName ?? '');
  const [busy, setBusy] = useState(false);
  const [page, setPage] = useState(0);
  const queryClient = useQueryClient();

  const recipes = useQuery({
    queryKey: ['userRecipes', profile?.id, page],
    queryFn: () => getUserRecipes(profile!.id, page),
    enabled: !!profile,
  });

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      const updated = await updateMe({ firstName, lastName });
      setProfile(updated);
      void queryClient.invalidateQueries(); // names appear in author lines, comments, public profile
      toast.success('Profile updated');
    } catch (err) {
      toast.error(err instanceof ApiError ? err.detail : 'Something went wrong');
    } finally {
      setBusy(false);
    }
  }

  if (!profile) return <Spinner />;

  return (
    <div>
      <ProfileHeader
        firstName={profile.firstName}
        lastName={profile.lastName}
        recipeTotal={recipes.data?.page.totalElements}
      />

      <section className="mt-10 max-w-3xl rounded-card border border-rule bg-surface p-5 sm:p-7">
        <h2 className="font-serif text-[22px] font-medium tracking-[-0.015em] text-ink">Profile</h2>
        <p className="mt-1 text-[15px] text-ink-soft">Your name appears next to your recipes and comments.</p>
        <form onSubmit={onSubmit} className="mt-5 grid gap-4 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
          <Field label="First name">
            <TextInput value={firstName} onChange={(e) => setFirstName(e.target.value)} required maxLength={255} />
          </Field>
          <Field label="Last name">
            <TextInput value={lastName} onChange={(e) => setLastName(e.target.value)} required maxLength={255} />
          </Field>
          <Button type="submit" disabled={busy}>Save names</Button>
        </form>
      </section>

      <SectionHeading>Two-factor authentication</SectionHeading>
      <section className="max-w-3xl rounded-card border border-rule bg-surface p-5 sm:p-7">
        <TwoFactorSettings />
      </section>

      <ProfileTabs
        mine={
          <>
            {recipes.isPending && <Spinner />}
            {recipes.isError && <p className="text-[15px] text-danger">Couldn't load recipes.</p>}
            {recipes.data && recipes.data.content.length === 0 && (
              <p className="rounded-card border border-dashed border-rule px-4 py-10 text-center text-[15px] text-ink-soft">
                Nothing yet —{' '}
                <Link
                  to="/recipes/new"
                  className="font-semibold text-ink underline decoration-rule underline-offset-4 transition-colors hover:decoration-ink"
                >
                  publish your first recipe
                </Link>
                .
              </p>
            )}
            {recipes.data && recipes.data.content.length > 0 && (
              <>
                <div className="grid grid-cols-2 gap-x-4 gap-y-8 lg:grid-cols-4">
                  {recipes.data.content.map((r) => <RecipeCard key={r.id} recipe={r} />)}
                </div>
                <Pagination page={recipes.data.page.number} totalPages={recipes.data.page.totalPages} onPage={setPage} />
              </>
            )}
          </>
        }
        saved={<SavedRecipesPanel />}
        ai={<SavedAiRecipes />}
        preferences={<PreferencesPanel />}
        needsAttention={profile.rejectedCount}
      />
    </div>
  );
}
