import { useId, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { SearchX, SlidersHorizontal } from 'lucide-react';
import { browseRecipes } from '@/api/recipes';
import { cuisinesQueryKey, getCuisines } from '@/api/cuisines';
import { RecipeCard } from '@/components/RecipeCard';
import { Pagination } from '@/components/Pagination';
import { Spinner, Button, EmptyState } from '@/components/ui';
import { countActive, emptyFilters, readFilters, toApiFilter, writeFilters, type FilterState } from './filterParams';
import { FilterDrawer } from './FilterDrawer';
import { ActiveFilterChips } from './ActiveFilterChips';

export default function BrowsePage() {
  const [params, setParams] = useSearchParams();
  const paramString = params.toString();
  const applied = useMemo(() => readFilters(new URLSearchParams(paramString)), [paramString]);
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);
  const active = countActive(applied);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const activeCountId = useId();

  const query = useQuery({
    queryKey: ['recipes', paramString],
    queryFn: () => browseRecipes(toApiFilter(applied), page),
    placeholderData: keepPreviousData,
  });

  // Catalogue labels for the cuisine chips; only needed once a cuisine filter is applied.
  const cuisines = useQuery({
    queryKey: cuisinesQueryKey,
    queryFn: getCuisines,
    staleTime: 60_000,
    enabled: applied.cuisine.length > 0,
  });

  const apply = (s: FilterState) => setParams(writeFilters(s, params));
  const busy = query.isPlaceholderData;
  const busyClass = busy ? 'pointer-events-none opacity-50' : '';

  return (
    <div>
      <section className="mb-8 sm:mb-10">
        <h1 className="max-w-2xl font-serif text-4xl leading-[1.08] font-semibold tracking-tight text-balance text-olive sm:text-5xl">
          Cook something <em className="font-medium text-terracotta italic">wonderful</em> tonight
        </h1>

        <div className="mt-6 flex flex-wrap items-center gap-2.5">
          <button
            type="button"
            aria-label="Filters"
            aria-describedby={active > 0 ? activeCountId : undefined}
            onClick={() => setDrawerOpen(true)}
            className={`inline-flex items-center gap-2 rounded-full border bg-paper py-2 pr-2.5 pl-4 text-sm font-semibold text-olive shadow-card transition duration-200 hover:-translate-y-px hover:shadow-lift focus-visible:outline-olive ${
              active > 0 ? 'border-olive/40' : 'border-sand pr-4'
            }`}
          >
            <SlidersHorizontal aria-hidden="true" className="size-4" strokeWidth={2.25} />
            Filters
            {active > 0 && (
              <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-saffron px-1.5 text-[11px] font-bold text-olive tabular-nums">
                {active}
              </span>
            )}
          </button>
          {active > 0 && (
            <span id={activeCountId} hidden>
              {active === 1 ? '1 filter active' : `${active} filters active`}
            </span>
          )}
          <ActiveFilterChips state={applied} cuisines={cuisines.data} onChange={apply} />
        </div>
      </section>

      <FilterDrawer open={drawerOpen} onOpenChange={setDrawerOpen} applied={applied} onApply={apply} />

      {query.isPending && <Spinner />}
      {query.isError && (
        <div className="py-12 text-center">
          <p className="mb-4 text-sm text-danger">Couldn't load recipes.</p>
          <Button variant="outline" onClick={() => query.refetch()}>Retry</Button>
        </div>
      )}
      {query.data && query.data.content.length === 0 && (
        <div aria-busy={busy} className={`transition-opacity ${busyClass}`}>
          <EmptyState
            icon={SearchX}
            action={<Button variant="outline" onClick={() => apply(emptyFilters)}>Clear filters</Button>}
          >
            No recipes match these filters.
          </EmptyState>
        </div>
      )}
      {query.data && query.data.content.length > 0 && (
        <>
          <div
            aria-busy={busy}
            className={`grid grid-cols-1 gap-6 transition-opacity sm:grid-cols-2 lg:grid-cols-4 ${busyClass}`}
          >
            {query.data.content.map((recipe) => (
              <RecipeCard key={recipe.id} recipe={recipe} />
            ))}
          </div>
          <Pagination
            page={query.data.page.number}
            totalPages={query.data.page.totalPages}
            onPage={(p) => {
              const next = new URLSearchParams(params);
              next.set('page', String(p));
              setParams(next);
            }}
          />
        </>
      )}
    </div>
  );
}
