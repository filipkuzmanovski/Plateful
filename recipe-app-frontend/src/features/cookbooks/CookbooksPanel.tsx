import { useId } from 'react';
import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { getCookbooks } from '@/api/cookbooks';
import { useAuth } from '@/auth/AuthProvider';
import { Button, SectionHeading, Spinner } from '@/components/ui';
import { useLikedIds } from '@/features/likes/useLikedIds';
import { MAX_COOKBOOKS, MIN_RECIPES } from './constants';
import { CoverThumb } from './CoverThumb';
import { LikedRecipesList } from './LikedRecipesList';

const CREATE_CLASSES =
  'inline-flex min-h-11 items-center justify-center rounded-full bg-ink px-5 py-2 text-[15px] font-semibold whitespace-nowrap text-on-ink transition-colors duration-200 ease-out hover:bg-ink/90';

/** The Cookbooks tab on the user's own profile: the shelf, the way to make a new book, and the liked recipes. */
export function CookbooksPanel() {
  const { profile } = useAuth();
  const { ids, ready } = useLikedIds();
  const messageId = useId();
  // Keyed by user so one account's cookbooks are never served to another in the same tab.
  const cookbooks = useQuery({
    queryKey: ['cookbooks', profile?.id],
    queryFn: getCookbooks,
    enabled: !!profile,
    refetchOnMount: 'always',
  });

  const ownerName = `${profile?.firstName ?? ''} ${profile?.lastName ?? ''}`.trim();
  const books = cookbooks.data ?? [];
  const atCap = books.length >= MAX_COOKBOOKS;
  const tooFewLikes = ids.length < MIN_RECIPES;

  let message: string | null = null;
  if (atCap) message = `You can have up to ${MAX_COOKBOOKS} cookbooks.`;
  else if (tooFewLikes) {
    const goal = books.length === 0 ? 'your first cookbook' : 'a cookbook';
    message = `${ids.length} of ${MIN_RECIPES} likes to make ${goal}`;
  }

  return (
    <div className="space-y-8">
      <section aria-label="Your cookbooks">
        {cookbooks.isPending && <Spinner />}
        {cookbooks.isError && <p className="text-[15px] text-danger">Couldn't load your cookbooks.</p>}
        {cookbooks.data && books.length === 0 && (
          <p className="rounded-card border border-dashed border-rule px-4 py-10 text-center text-[15px] text-ink-soft">
            No cookbooks yet. Like {MIN_RECIPES} recipes, then turn your favourites into a small book of your own.
          </p>
        )}
        {books.length > 0 && (
          <ul className="grid grid-cols-2 gap-4 sm:grid-cols-4 lg:grid-cols-6">
            {books.map((book) => (
              <li key={book.id}>
                <CoverThumb book={book} ownerName={ownerName} />
              </li>
            ))}
          </ul>
        )}
        {ready && cookbooks.data && (
          <div className="mt-5 flex flex-wrap items-center gap-x-4 gap-y-2">
            {message === null ? (
              <Link to="/cookbooks/new" className={CREATE_CLASSES}>
                Create a cookbook
              </Link>
            ) : (
              <>
                <Button type="button" aria-disabled="true" aria-describedby={messageId} className="cursor-not-allowed opacity-60">
                  Create a cookbook
                </Button>
                <p id={messageId} className="text-[15px] text-ink-soft">{message}</p>
              </>
            )}
          </div>
        )}
      </section>

      <section>
        <SectionHeading>Liked recipes</SectionHeading>
        <LikedRecipesList />
      </section>
    </div>
  );
}
