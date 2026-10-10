import { useState } from 'react';
import { useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { getUser, getUserRecipes } from '@/api/users';
import { RecipeCard } from '@/components/RecipeCard';
import { Pagination } from '@/components/Pagination';
import { UserRoundX } from 'lucide-react';
import { EmptyState, SectionHeading, Spinner } from '@/components/ui';
import { ProfileHeader } from './ProfileHeader';

function PublicProfileContent({ id }: { id: string }) {
  const [page, setPage] = useState(0);
  const user = useQuery({ queryKey: ['user', id], queryFn: () => getUser(id) });
  const recipes = useQuery({
    queryKey: ['userRecipes', id, page],
    queryFn: () => getUserRecipes(id, page),
  });

  if (user.isPending) return <Spinner />;
  if (user.isError) return <EmptyState icon={UserRoundX}>User not found.</EmptyState>;

  return (
    <div>
      <ProfileHeader
        firstName={user.data.firstName}
        lastName={user.data.lastName}
        recipeTotal={recipes.data?.page.totalElements}
      />
      <SectionHeading>Recipes</SectionHeading>
      {recipes.isPending && <Spinner />}
      {recipes.isError && <p className="text-sm text-danger">Couldn't load recipes.</p>}
      {recipes.data && recipes.data.content.length === 0 && (
        <p className="rounded-card border border-dashed border-sand px-4 py-10 text-center text-sm text-olive/70">
          No recipes published yet.
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

export default function PublicProfilePage() {
  const { id } = useParams<{ id: string }>();
  return <PublicProfileContent key={id} id={id!} />;
}
