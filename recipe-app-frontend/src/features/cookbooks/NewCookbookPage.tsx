import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Heart } from 'lucide-react';
import { ApiError } from '@/api/client';
import { createCookbook } from '@/api/cookbooks';
import { getLikedRecipes } from '@/api/likes';
import { useAuth } from '@/auth/AuthProvider';
import { Button, EmptyState, Field, Spinner, TextInput } from '@/components/ui';
import type { RecipeDisplay } from '@/lib/types';
import { DEFAULT_SUBTITLE, MAX_RECIPES, MAX_SUBTITLE, MIN_RECIPES } from './constants';
import { PickGrid } from './PickGrid';

/** Choose 5 to 10 liked recipes, in chapter order, and make them into a cookbook. */
export default function NewCookbookPage() {
  const { profile } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<string[]>([]);
  const [subtitle, setSubtitle] = useState('');
  const [error, setError] = useState<string | null>(null);

  const liked = useInfiniteQuery({
    queryKey: ['likedPicker', profile?.id],
    queryFn: ({ pageParam }) => getLikedRecipes(pageParam),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page.number + 1 < last.page.totalPages ? last.page.number + 1 : undefined),
    enabled: !!profile,
    refetchOnMount: 'always',
  });

  // Hidden recipes come back as stubs and cannot go in a book.
  const recipes = useMemo<RecipeDisplay[]>(
    () => (liked.data?.pages ?? []).flatMap((page) => page.content.flatMap((item) => (item.recipe ? [item.recipe] : []))),
    [liked.data],
  );

  // After a reload a ticked recipe may no longer be offered, so it leaves the choice too.
  useEffect(() => {
    if (!liked.data) return;
    const offered = new Set(recipes.map((recipe) => recipe.id));
    setSelected((current) => (current.every((id) => offered.has(id)) ? current : current.filter((id) => offered.has(id))));
  }, [liked.data, recipes]);

  const create = useMutation({
    mutationFn: () => {
      const trimmed = subtitle.trim();
      return createCookbook({ recipeIds: selected, ...(trimmed ? { subtitle: trimmed } : {}) });
    },
    onSuccess: (book) => {
      void queryClient.invalidateQueries({ queryKey: ['cookbooks'] });
      navigate(`/cookbooks/${book.id}`);
    },
    onError: (e) => {
      setError(e instanceof ApiError ? e.detail : "Couldn't create the cookbook. Please try again.");
      void liked.refetch();
    },
  });

  const full = selected.length >= MAX_RECIPES;
  const canCreate = selected.length >= MIN_RECIPES && !create.isPending;
  const tooFew = !liked.hasNextPage && recipes.length < MIN_RECIPES;

  const toggle = (id: string) =>
    setSelected((current) => (current.includes(id) ? current.filter((x) => x !== id) : [...current, id]));

  const backLink = (
    <Link
      to="/me?tab=cookbooks"
      className="text-[15px] text-ink underline decoration-rule underline-offset-4 transition hover:decoration-ink"
    >
      Back to your cookbooks
    </Link>
  );

  let body;
  if (liked.isPending) body = <Spinner />;
  else if (liked.isError) body = <p className="text-[15px] text-danger">Couldn't load your liked recipes.</p>;
  else if (liked.data.pages[0].content.length === 0) {
    body = <EmptyState icon={Heart}>No liked recipes yet. Tap the heart on any recipe to like it.</EmptyState>;
  } else {
    body = (
      <>
        <PickGrid recipes={recipes} selected={selected} full={full} onToggle={toggle} />
        {liked.hasNextPage && (
          <div className="mt-6 flex justify-center">
            <Button type="button" variant="outline" disabled={liked.isFetchingNextPage} onClick={() => void liked.fetchNextPage()}>
              Load more
            </Button>
          </div>
        )}
      </>
    );
  }

  return (
    <div className="space-y-6">
      <header className="space-y-2">
        {backLink}
        <h1 className="font-serif text-4xl leading-[1.05] font-medium tracking-[-0.015em] text-balance text-ink">
          Create a cookbook
        </h1>
        <p className="text-base text-ink-soft">
          Choose {MIN_RECIPES} to {MAX_RECIPES} recipes you liked. The order you tick them is the order of the chapters.
        </p>
      </header>

      {tooFew && liked.data && liked.data.pages[0].content.length > 0 && (
        <p className="rounded-card bg-tint px-4 py-3 text-[15px] text-ink">
          Only {recipes.length} of your liked recipes are available. A cookbook needs at least {MIN_RECIPES}.
        </p>
      )}

      {body}

      <div className="max-w-md">
        <Field label="Subtitle (optional)">
          <TextInput
            type="text"
            value={subtitle}
            maxLength={MAX_SUBTITLE}
            placeholder={DEFAULT_SUBTITLE}
            onChange={(e) => setSubtitle(e.target.value)}
          />
        </Field>
      </div>

      {error && (
        <p role="alert" className="text-[15px] font-medium text-danger">
          {error}
        </p>
      )}

      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <Button
          type="button"
          aria-disabled={!canCreate || undefined}
          onClick={() => {
            if (!canCreate) return;
            setError(null);
            create.mutate();
          }}
          className="aria-disabled:cursor-not-allowed aria-disabled:opacity-60"
        >
          Create cookbook
        </Button>
        <p aria-live="polite" className="text-[15px] text-ink-soft tabular-nums">
          {selected.length} of {MIN_RECIPES} to {MAX_RECIPES} chosen
          {full && '. That is the most one cookbook can hold.'}
        </p>
      </div>
    </div>
  );
}
