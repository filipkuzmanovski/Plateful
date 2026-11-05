import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Heart } from 'lucide-react';
import { getLikedRecipes } from '@/api/likes';
import { useAuth } from '@/auth/AuthProvider';
import { Pagination } from '@/components/Pagination';
import { RecipeCard } from '@/components/RecipeCard';
import { EmptyState, Spinner } from '@/components/ui';
import { SavedStubCard } from '@/features/saved/SavedStubCard';

/**
 * The signed-in user's liked recipes, newest like first; a hidden one shows as a stub under its approved title.
 * Unliking does not refetch this list (see useLikedIds), so the card stays until the list next loads.
 */
export function LikedRecipesList() {
  const { profile } = useAuth();
  const [page, setPage] = useState(0);
  // Keyed by user so one account's list is never served to another in the same tab.
  const liked = useQuery({
    queryKey: ['likedRecipes', profile?.id, page],
    queryFn: () => getLikedRecipes(page),
    enabled: !!profile,
    refetchOnMount: 'always',
  });

  // A reload after unliking can leave a later page empty: step back to the last page that still has recipes.
  const emptyLaterPage = !!liked.data && liked.data.content.length === 0 && page > 0;
  const lastPage = liked.data ? Math.max(0, liked.data.page.totalPages - 1) : 0;
  useEffect(() => {
    if (emptyLaterPage) setPage(Math.min(page - 1, lastPage));
  }, [emptyLaterPage, page, lastPage]);

  if (liked.isPending || emptyLaterPage) return <Spinner />;
  if (liked.isError) return <p className="text-[15px] text-danger">Couldn't load liked recipes.</p>;

  if (liked.data.content.length === 0) {
    return (
      <EmptyState
        icon={Heart}
        action={
          <Link
            to="/"
            className="inline-flex min-h-11 items-center justify-center rounded-full bg-ink px-5 py-2 text-[15px] font-semibold whitespace-nowrap text-on-ink transition-colors duration-200 ease-out hover:bg-ink/90"
          >
            Browse recipes
          </Link>
        }
      >
        No liked recipes yet. Tap the heart on any recipe to like it.
      </EmptyState>
    );
  }

  return (
    <>
      <div className="grid grid-cols-2 gap-x-4 gap-y-8 lg:grid-cols-4">
        {liked.data.content.map((item) =>
          item.kind === 'recipe' ? (
            <RecipeCard key={item.recipe.id} recipe={item.recipe} />
          ) : (
            <SavedStubCard key={item.stub.id} id={item.stub.id} approvedTitle={item.stub.approvedTitle} state={item.stub.state} />
          ),
        )}
      </div>
      <Pagination page={liked.data.page.number} totalPages={liked.data.page.totalPages} onPage={setPage} />
    </>
  );
}
