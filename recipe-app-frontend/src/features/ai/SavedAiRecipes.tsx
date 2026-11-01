import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Sparkles } from 'lucide-react';
import { aiSavedQueryKey, listSavedAiRecipes, unsaveAiRecipe } from '@/api/ai';
import { useAuth } from '@/auth/AuthProvider';
import { Pagination } from '@/components/Pagination';
import { Button, EmptyState, Spinner } from '@/components/ui';
import type { GeneratedRecipeSummary, MealType } from '@/lib/types';
import { aiErrorMessage } from './aiMessages';

const MEAL_LABEL: Record<MealType, string> = { breakfast: 'Breakfast', lunch: 'Lunch', dinner: 'Dinner' };

function kindLabel(r: GeneratedRecipeSummary): string {
  return r.kind === 'daily' && r.mealType ? MEAL_LABEL[r.mealType] : 'Cook';
}

/** "2026-10-28T09:30:00Z" -> "28 October" (the instant, shown in the reader's own time zone). */
function savedDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'long' });
}

/** The signed-in user's saved AI recipes, newest saved first, with a Remove button on each. */
export function SavedAiRecipes() {
  const { profile } = useAuth();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const saved = useQuery({
    queryKey: aiSavedQueryKey(profile?.id, page),
    queryFn: () => listSavedAiRecipes(page),
    enabled: !!profile,
  });

  const remove = useMutation({
    mutationFn: (id: string) => unsaveAiRecipe(id),
    onSuccess: (_data, id) => {
      void queryClient.invalidateQueries({ queryKey: ['aiSaved'] });
      void queryClient.invalidateQueries({ queryKey: ['aiRecipe', id] });
    },
    onError: (e) => toast.error(aiErrorMessage(e)),
  });

  // Removing the last row of a page leaves it empty: step back to the last page that still has recipes.
  const emptyLaterPage = !!saved.data && saved.data.content.length === 0 && page > 0;
  const lastPage = saved.data ? Math.max(0, saved.data.page.totalPages - 1) : 0;
  useEffect(() => {
    if (emptyLaterPage) setPage(Math.min(page - 1, lastPage));
  }, [emptyLaterPage, page, lastPage]);

  if (saved.isPending || emptyLaterPage) return <Spinner />;
  if (saved.isError) return <p className="text-sm text-danger">Couldn't load saved AI recipes.</p>;

  if (saved.data.content.length === 0) {
    return <EmptyState icon={Sparkles}>No saved AI recipes yet.</EmptyState>;
  }

  return (
    <>
      <ul className="grid gap-3">
        {saved.data.content.map((r) => (
          <li
            key={r.id}
            className="flex flex-wrap items-center justify-between gap-3 rounded-card bg-paper p-4 shadow-card ring-1 ring-sand/70 sm:px-5"
          >
            <div className="min-w-0">
              <Link
                to={`/ai-recipes/${r.id}`}
                className="font-serif text-lg font-semibold text-olive underline decoration-olive/30 underline-offset-4 transition hover:decoration-saffron"
              >
                {r.title}
              </Link>
              <p className="mt-1 text-sm text-olive/70">
                <span className="font-semibold">{kindLabel(r)}</span>
                {r.savedAt && <> · Saved {savedDate(r.savedAt)}</>}
              </p>
            </div>
            <Button
              type="button"
              variant="outline"
              aria-label={`Remove ${r.title} from AI recipes`}
              disabled={remove.isPending && remove.variables === r.id}
              onClick={() => remove.mutate(r.id)}
              className="!px-4 !py-2"
            >
              Remove
            </Button>
          </li>
        ))}
      </ul>
      <Pagination page={saved.data.page.number} totalPages={saved.data.page.totalPages} onPage={setPage} />
    </>
  );
}
