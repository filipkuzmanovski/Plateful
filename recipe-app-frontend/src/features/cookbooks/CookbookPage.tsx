import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CircleAlert, SearchX } from 'lucide-react';
import { toast } from 'sonner';
import { ApiError } from '@/api/client';
import { deleteCookbook, getCookbook } from '@/api/cookbooks';
import { cuisinesQueryKey, getCuisines } from '@/api/cuisines';
import { useAuth } from '@/auth/AuthProvider';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { Button, EmptyState, Spinner } from '@/components/ui';
import { humanizeKey } from '@/features/browse/regions';
import { bookFacts, leafFromParam } from './bookFacts';
import { ChapterLeaf, CoverLeaf, OpeningLeaf, StubChapterLeaf } from './bookLeaves';

/** Cover is leaf 1, the opening leaf 2, and the chapters follow. */
const FIRST_CHAPTER_LEAF = 3;

const collectedFormat = new Intl.DateTimeFormat('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' });

/**
 * A cookbook read as a small book, one leaf at a time, with the leaf kept in the URL. Every leaf is always in the
 * page and the others are only hidden, so printing needs no extra fetch.
 */
export default function CookbookPage() {
  const { id } = useParams<{ id: string }>();
  const { profile } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const [deleting, setDeleting] = useState(false);

  // Keyed by user so one account's book is never served to another in the same tab.
  const query = useQuery({
    queryKey: ['cookbook', id, profile?.id],
    queryFn: () => getCookbook(id!),
    enabled: !!profile,
    retry: false,
  });
  const cuisines = useQuery({ queryKey: cuisinesQueryKey, queryFn: getCuisines, staleTime: Infinity });

  const remove = useMutation({
    mutationFn: () => deleteCookbook(id!),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['cookbooks'] });
      queryClient.removeQueries({ queryKey: ['cookbook', id] });
      navigate('/me?tab=cookbooks');
    },
    onError: (e) => {
      setDeleting(false);
      toast.error(e instanceof ApiError ? e.detail : "Couldn't delete the cookbook. Please try again.");
    },
  });

  const book = query.data;
  const chapterCount = book?.chapters.length ?? 0;
  const leafCount = chapterCount === 0 ? 1 : FIRST_CHAPTER_LEAF - 1 + chapterCount;
  const leaf = leafFromParam(params.get('page'), leafCount);

  const goTo = (target: number) => {
    if (target < 1 || target > leafCount || target === leaf) return;
    setParams(target === 1 ? {} : { page: String(target) });
  };

  // Turning a page moves focus to the new leaf's heading; opening the book does not.
  const root = useRef<HTMLDivElement>(null);
  const shownLeaf = useRef<number | null>(null);
  const loaded = !!book;
  useEffect(() => {
    if (!loaded) return;
    if (shownLeaf.current !== null && shownLeaf.current !== leaf) {
      root.current?.querySelector<HTMLElement>(`[data-leaf="${leaf}"] [data-leaf-heading]`)?.focus();
    }
    shownLeaf.current = leaf;
  }, [loaded, leaf]);

  // Arrow keys turn pages, unless they are busy elsewhere: in a field, in the dialog, or with a modifier held.
  useEffect(() => {
    if (!loaded || leafCount === 1) return;
    function onKeyDown(e: KeyboardEvent) {
      if (deleting || e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;
      const target = e.target as Element | null;
      if (target?.closest?.('input, textarea, select, [contenteditable], [role="dialog"]')) return;
      if (e.key === 'ArrowRight' && leaf < leafCount) {
        e.preventDefault();
        setParams({ page: String(leaf + 1) });
      } else if (e.key === 'ArrowLeft' && leaf > 1) {
        e.preventDefault();
        setParams(leaf === 2 ? {} : { page: String(leaf - 1) });
      }
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [loaded, leaf, leafCount, deleting, setParams]);

  if (query.isPending) return <Spinner />;
  if (query.isError) {
    const notFound = query.error instanceof ApiError && query.error.status === 404;
    return (
      <EmptyState icon={notFound ? SearchX : CircleAlert}>
        {notFound ? 'Cookbook not found.' : "Couldn't load this cookbook."}
      </EmptyState>
    );
  }

  const labelOf = (key: string) => cuisines.data?.find((c) => c.key === key)?.label ?? humanizeKey(key);
  const data = query.data;

  const leafProps = (number: number) => ({
    'data-leaf': number,
    'data-active': number === leaf,
    className: number === leaf ? 'block motion-safe:animate-view-in' : 'hidden print:block',
  });

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3 print:hidden">
        <Link
          to="/me?tab=cookbooks"
          className="text-[15px] text-ink underline decoration-rule underline-offset-4 transition hover:decoration-ink"
        >
          Back to your cookbooks
        </Link>
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="outline" onClick={() => setDeleting(true)}>
            Delete
          </Button>
        </div>
      </div>

      <div ref={root}>
        <section {...leafProps(1)}>
          <CoverLeaf book={data} />
        </section>
        {chapterCount > 0 && (
          <section {...leafProps(2)}>
            <OpeningLeaf
              book={data}
              facts={bookFacts(data.chapters, labelOf)}
              collected={collectedFormat.format(new Date(data.createdAt))}
              firstChapterLeaf={FIRST_CHAPTER_LEAF}
            />
          </section>
        )}
        {data.chapters.map((item, i) => (
          <section key={item.recipe?.id ?? item.stub?.id} {...leafProps(FIRST_CHAPTER_LEAF + i)}>
            {item.recipe ? (
              <ChapterLeaf recipe={item.recipe} number={i + 1} />
            ) : (
              <StubChapterLeaf title={item.stub.approvedTitle} state={item.stub.state} number={i + 1} />
            )}
          </section>
        ))}
      </div>

      {chapterCount === 0 && (
        <p className="text-center text-base text-ink-soft print:hidden">
          The recipes in this book are gone, because their authors or our admins removed them. You can delete it.
        </p>
      )}

      {leafCount > 1 && (
        <nav aria-label="Pages of this book" className="flex items-center justify-center gap-4 print:hidden">
          <Button
            type="button"
            variant="outline"
            aria-label="Previous page"
            aria-disabled={leaf === 1 || undefined}
            onClick={() => goTo(leaf - 1)}
            className="aria-disabled:cursor-not-allowed aria-disabled:opacity-45"
          >
            Previous
          </Button>
          <span className="text-[15px] text-ink-soft tabular-nums">
            Page {leaf} of {leafCount}
          </span>
          <Button
            type="button"
            variant="outline"
            aria-label="Next page"
            aria-disabled={leaf === leafCount || undefined}
            onClick={() => goTo(leaf + 1)}
            className="aria-disabled:cursor-not-allowed aria-disabled:opacity-45"
          >
            Next
          </Button>
        </nav>
      )}

      <ConfirmDialog
        open={deleting}
        title="Delete this cookbook?"
        description="This removes the book only. The recipes and your likes stay as they are."
        confirmLabel="Delete cookbook"
        busy={remove.isPending}
        onConfirm={() => remove.mutate()}
        onCancel={() => setDeleting(false)}
      />
    </div>
  );
}
