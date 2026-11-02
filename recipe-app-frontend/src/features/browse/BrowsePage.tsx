import { useId, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import * as Select from '@radix-ui/react-select';
import { ArrowUpDown, ChevronDown, Clock, MessageCircle, SearchX, SlidersHorizontal, Star, Users, type LucideIcon } from 'lucide-react';
import { browseRecipes, PAGE_SIZE } from '@/api/recipes';
import { cuisinesQueryKey, getCuisines } from '@/api/cuisines';
import { RecipeCard } from '@/components/RecipeCard';
import { Pagination } from '@/components/Pagination';
import { Spinner, Button, EmptyState } from '@/components/ui';
import { SelectMenuContent, SelectMenuItem } from '@/components/SelectMenu';
import { countActive, emptyFilters, readFilters, readQuery, readSort, SORT_KEYS, SORT_LABELS, toApiFilter, writeFilters, writeQuery, writeSort, type FilterState, type SortKey } from './filterParams';
import { FilterDrawer } from './FilterDrawer';
import { ActiveFilterChips } from './ActiveFilterChips';
import { SearchBar } from './SearchBar';
import { TodaysMeals } from '@/features/ai/TodaysMeals';

const SORT_ICONS: Record<SortKey, LucideIcon> = {
  newest: Clock,
  'most-rated': Users,
  'top-rated': Star,
  'most-comments': MessageCircle,
};

export default function BrowsePage() {
  const [params, setParams] = useSearchParams();
  const paramString = params.toString();
  const applied = useMemo(() => readFilters(new URLSearchParams(paramString)), [paramString]);
  const sort = readSort(params);
  const q = readQuery(params);
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);
  const active = countActive(applied);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const activeCountId = useId();

  const query = useQuery({
    queryKey: ['recipes', paramString],
    queryFn: () => browseRecipes(toApiFilter(applied), page, PAGE_SIZE, sort, q),
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
        <h1 className="max-w-2xl font-serif text-4xl leading-[1.08] font-medium tracking-[-0.015em] text-balance text-ink sm:text-5xl">
          Find tonight's dinner
        </h1>

        <SearchBar value={q} onSearch={(next) => setParams((prev) => writeQuery(next, prev))} />

        <div className="mt-3 flex flex-wrap items-center gap-2.5">
          <button
            type="button"
            aria-label="Filters"
            aria-describedby={active > 0 ? activeCountId : undefined}
            onClick={() => setDrawerOpen(true)}
            className={`inline-flex min-h-11 items-center gap-2 rounded-full border bg-surface py-2 text-[15px] font-semibold text-ink transition-colors duration-200 hover:bg-tint ${
              active > 0 ? 'border-ink pr-2.5 pl-4' : 'border-field px-4'
            }`}
          >
            <SlidersHorizontal aria-hidden="true" className="size-4" strokeWidth={2.25} />
            Filters
            {active > 0 && (
              <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-accent px-1.5 text-[12px] font-bold text-on-accent tabular-nums">
                {active}
              </span>
            )}
          </button>
          {active > 0 && (
            <span id={activeCountId} hidden>
              {active === 1 ? '1 filter active' : `${active} filters active`}
            </span>
          )}
          <Select.Root value={sort} onValueChange={(value) => setParams(writeSort(value as SortKey, params))}>
            <Select.Trigger
              aria-label={`Sort by: ${SORT_LABELS[sort]}`}
              className="group inline-flex min-h-11 max-w-full items-center gap-2 rounded-full border border-field bg-surface py-2 pr-3 pl-4 text-[15px] font-semibold text-ink transition-colors duration-200 hover:bg-tint data-[state=open]:border-ink data-[state=open]:bg-tint"
            >
              <ArrowUpDown aria-hidden="true" className="size-4 shrink-0" strokeWidth={2.25} />
              <span className="truncate">
                <Select.Value>Sort by: {SORT_LABELS[sort]}</Select.Value>
              </span>
              <Select.Icon asChild>
                <ChevronDown
                  aria-hidden="true"
                  className="size-4 shrink-0 text-ink-soft transition-transform duration-200 group-data-[state=open]:rotate-180"
                  strokeWidth={2.25}
                />
              </Select.Icon>
            </Select.Trigger>
            <SelectMenuContent>
              {SORT_KEYS.map((key) => (
                <SelectMenuItem key={key} value={key} icon={SORT_ICONS[key]}>
                  {SORT_LABELS[key]}
                </SelectMenuItem>
              ))}
            </SelectMenuContent>
          </Select.Root>
          <ActiveFilterChips state={applied} cuisines={cuisines.data} onChange={apply} />
        </div>
      </section>

      <TodaysMeals />

      <FilterDrawer open={drawerOpen} onOpenChange={setDrawerOpen} applied={applied} query={q} onApply={apply} />

      {query.isPending && <Spinner />}
      {query.isError && (
        <div className="py-12 text-center">
          <p className="mb-4 text-[15px] text-danger">Couldn't load recipes.</p>
          <Button variant="outline" onClick={() => query.refetch()}>Retry</Button>
        </div>
      )}
      {query.data && query.data.content.length === 0 && (
        <div aria-busy={busy} className={`transition-opacity ${busyClass}`}>
          <EmptyState
            icon={SearchX}
            action={<Button variant="outline" onClick={() => setParams(writeQuery('', writeFilters(emptyFilters, params)))}>Clear filters</Button>}
          >
            No recipes match your search or filters.
          </EmptyState>
        </div>
      )}
      {query.data && query.data.content.length > 0 && (
        <>
          <div
            aria-busy={busy}
            className={`grid grid-cols-2 gap-x-4 gap-y-8 lg:grid-cols-4 transition-opacity ${busyClass}`}
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
