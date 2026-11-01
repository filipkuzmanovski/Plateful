import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { ArrowUpRight, RefreshCw } from 'lucide-react';
import { aiRecipeQueryKey, getAiRecipe } from '@/api/ai';
import { Button } from '@/components/ui';
import type { GeneratedRecipe } from '@/lib/types';
import { AiRecipeView } from './AiRecipeView';
import { SaveAiRecipeButton } from './SaveAiRecipeButton';

const outlineLink =
  'inline-flex items-center justify-center gap-2 rounded-full border border-olive/20 bg-paper px-4 py-2 text-sm font-semibold whitespace-nowrap text-olive transition duration-200 hover:border-olive/50 hover:bg-cream focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-olive';

/** The generated recipe with its actions. The recipe is read through the cache so Save/Saved stays current. */
export function CookResult({
  recipe, onAgain, busy,
}: { recipe: GeneratedRecipe; onAgain: () => void; busy: boolean }) {
  // The fresh recipe seeds the cache; Save/Unsave invalidate it, which refetches the saved flag.
  const query = useQuery({
    queryKey: aiRecipeQueryKey(recipe.id),
    queryFn: () => getAiRecipe(recipe.id),
    initialData: recipe,
    staleTime: Infinity,
  });
  const current = query.data;

  return (
    <AiRecipeView
      recipe={current}
      actions={
        <>
          <Button type="button" variant="outline" disabled={busy} onClick={onAgain} className="!px-4 !py-2">
            <RefreshCw aria-hidden="true" className="size-3.5" strokeWidth={2} />
            Generate another
          </Button>
          <SaveAiRecipeButton id={current.id} saved={current.saved} />
          <Link to={`/ai-recipes/${encodeURIComponent(current.id)}`} className={outlineLink}>
            Open
            <ArrowUpRight aria-hidden="true" className="size-3.5" strokeWidth={2} />
          </Link>
        </>
      }
    />
  );
}
