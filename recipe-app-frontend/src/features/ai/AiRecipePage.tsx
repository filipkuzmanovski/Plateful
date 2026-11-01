import { Link, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { CircleAlert, SearchX } from 'lucide-react';
import { aiRecipeQueryKey, getAiRecipe } from '@/api/ai';
import { ApiError } from '@/api/client';
import { EmptyState, Spinner } from '@/components/ui';
import { AiRecipeView } from './AiRecipeView';
import { SaveAiRecipeButton } from './SaveAiRecipeButton';

export default function AiRecipePage() {
  const { id } = useParams<{ id: string }>();
  const query = useQuery({ queryKey: aiRecipeQueryKey(id!), queryFn: () => getAiRecipe(id!), retry: false });

  if (query.isPending) return <Spinner />;
  if (query.isError) {
    const notFound = query.error instanceof ApiError && query.error.status === 404;
    return (
      <EmptyState
        icon={notFound ? SearchX : CircleAlert}
        action={
          notFound ? undefined : (
            <Link to="/" className="text-sm font-semibold text-olive underline underline-offset-4 hover:decoration-saffron">
              Back to all recipes
            </Link>
          )
        }
      >
        {notFound ? 'Page not found.' : "Couldn't load this recipe."}
      </EmptyState>
    );
  }
  const recipe = query.data;
  return <AiRecipeView recipe={recipe} actions={<SaveAiRecipeButton id={recipe.id} saved={recipe.saved} />} />;
}
