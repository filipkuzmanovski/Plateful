import { useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { browseRecipes } from '@/api/recipes';
import type { RecipeFilter } from '@/lib/types';
import { RecipeCard } from '@/components/RecipeCard';
import { Pagination } from '@/components/Pagination';
import { Spinner, Button } from '@/components/ui';
import { FilterBar, type FilterValues } from './FilterBar';

function readFilters(params: URLSearchParams): FilterValues {
  return {
    cuisine: params.get('cuisine') ?? '',
    vegan: params.get('vegan') === 'true',
    vegetarian: params.get('vegetarian') === 'true',
    glutenFree: params.get('glutenFree') === 'true',
    dairyFree: params.get('dairyFree') === 'true',
    maxReadyInMinutes: params.get('maxReadyInMinutes') ?? '',
    minRating: params.get('minRating') ?? '',
  };
}

function toNumber(s: string): number | undefined {
  const n = Number(s);
  return s !== '' && Number.isFinite(n) ? n : undefined;
}

function toApiFilter(v: FilterValues): RecipeFilter {
  return {
    cuisine: v.cuisine || undefined,
    vegan: v.vegan || undefined,
    vegetarian: v.vegetarian || undefined,
    glutenFree: v.glutenFree || undefined,
    dairyFree: v.dairyFree || undefined,
    maxReadyInMinutes: toNumber(v.maxReadyInMinutes),
    minRating: toNumber(v.minRating),
  };
}

export default function BrowsePage() {
  const [params, setParams] = useSearchParams();
  const values = readFilters(params);
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);

  const query = useQuery({
    queryKey: ['recipes', params.toString()],
    queryFn: () => browseRecipes(toApiFilter(values), page),
    placeholderData: keepPreviousData,
  });

  function patchParams(patch: Partial<FilterValues>) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(patch)) {
      if (value === '' || value === false) next.delete(key);
      else next.set(key, String(value));
    }
    next.delete('page'); // filter change resets pagination
    setParams(next);
  }

  const busyClass = query.isPlaceholderData ? ' opacity-50' : '';

  return (
    <div>
      <FilterBar values={values} onChange={patchParams} />

      {query.isPending && <Spinner />}
      {query.isError && (
        <div className="py-12 text-center">
          <p className="mb-4 text-sm text-red-600">Couldn't load recipes.</p>
          <Button variant="outline" onClick={() => query.refetch()}>Retry</Button>
        </div>
      )}
      {query.data && query.data.content.length === 0 && (
        <div aria-busy={query.isPlaceholderData} className={`py-12 text-center transition-opacity${busyClass}`}>
          <p className="mb-4 text-muted">No recipes match these filters.</p>
          <Button variant="outline" onClick={() => setParams(new URLSearchParams())}>Clear filters</Button>
        </div>
      )}
      {query.data && query.data.content.length > 0 && (
        <>
          <div
            aria-busy={query.isPlaceholderData}
            className={`grid grid-cols-1 gap-5 transition-opacity sm:grid-cols-2 lg:grid-cols-4${query.isPlaceholderData ? ' opacity-50 pointer-events-none' : ''}`}
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
