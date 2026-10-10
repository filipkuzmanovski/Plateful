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
      <h1 className="text-2xl font-extrabold tracking-tight">
        {profile.firstName} {profile.lastName}
      </h1>

      <SectionHeading>Profile</SectionHeading>
      <form onSubmit={onSubmit} className="flex max-w-lg flex-wrap items-end gap-3">
        <Field label="First name">
          <TextInput value={firstName} onChange={(e) => setFirstName(e.target.value)} required maxLength={255} />
        </Field>
        <Field label="Last name">
          <TextInput value={lastName} onChange={(e) => setLastName(e.target.value)} required maxLength={255} />
        </Field>
        <Button type="submit" disabled={busy}>Save names</Button>
      </form>

      <SectionHeading>My recipes</SectionHeading>
      {recipes.isPending && <Spinner />}
      {recipes.isError && <p className="text-sm text-red-600">Couldn't load recipes.</p>}
      {recipes.data && recipes.data.content.length === 0 && (
        <p className="text-sm text-muted">
          Nothing yet — <Link to="/recipes/new" className="text-accent underline">publish your first recipe</Link>.
        </p>
      )}
      {recipes.data && recipes.data.content.length > 0 && (
        <>
          <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {recipes.data.content.map((r) => <RecipeCard key={r.id} recipe={r} />)}
          </div>
          <Pagination page={recipes.data.page.number} totalPages={recipes.data.page.totalPages} onPage={setPage} />
        </>
      )}
    </div>
  );
}
