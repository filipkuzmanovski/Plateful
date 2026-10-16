import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Bookmark } from 'lucide-react';
import { getSavedRecipes } from '@/api/saved';
import { RecipeCard } from '@/components/RecipeCard';
import { Pagination } from '@/components/Pagination';
import { EmptyState, Spinner } from '@/components/ui';
import { useAuth } from '@/auth/AuthProvider';

/** The signed-in user's saved recipes, newest saved first. Unsaving a card refetches this list via ['savedRecipes']. */
export function SavedRecipesPanel() {
  const { profile } = useAuth();
  const [page, setPage] = useState(0);
  // Keyed by user so one account's private list is never served to another in the same tab.
  // Invalidating the ['savedRecipes'] prefix still matches.
  const saved = useQuery({
    queryKey: ['savedRecipes', profile?.id, page],
    queryFn: () => getSavedRecipes(page),
    enabled: !!profile,
  });

  // Unsaving the last card of a page leaves it empty: step back to the last page that still has recipes.
  const emptyLaterPage = !!saved.data && saved.data.content.length === 0 && page > 0;
  const lastPage = saved.data ? Math.max(0, saved.data.page.totalPages - 1) : 0;
  useEffect(() => {
    if (emptyLaterPage) setPage(Math.min(page - 1, lastPage));
  }, [emptyLaterPage, page, lastPage]);

  if (saved.isPending || emptyLaterPage) return <Spinner />;
  if (saved.isError) return <p className="text-sm text-danger">Couldn't load saved recipes.</p>;

  if (saved.data.content.length === 0) {
    return (
      <EmptyState
        icon={Bookmark}
        action={
          <Link
            to="/"
            className="inline-flex items-center justify-center rounded-full bg-olive px-5 py-2.5 text-sm font-semibold whitespace-nowrap text-cream shadow-button transition duration-200 ease-out hover:-translate-y-px hover:bg-olive/90 hover:shadow-lift"
          >
            Browse recipes
          </Link>
        }
      >
        No saved recipes yet. Tap the bookmark on any recipe to save it here.
      </EmptyState>
    );
  }

  return (
    <>
      <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
        {saved.data.content.map((r) => <RecipeCard key={r.id} recipe={r} />)}
      </div>
      <Pagination page={saved.data.page.number} totalPages={saved.data.page.totalPages} onPage={setPage} />
    </>
  );
}
