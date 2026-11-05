import { Link } from 'react-router';
import { ExternalLink } from 'lucide-react';
import type { RecipeDisplay } from '@/lib/types';

/** Who a recipe is by: its author, or the source of an imported recipe. Nothing when it has neither. */
export function RecipeCredit({ recipe }: { recipe: RecipeDisplay }) {
  if (recipe.user) {
    return (
      <Link
        to={`/users/${recipe.user.id}`}
        className="text-[15px] text-ink underline decoration-rule underline-offset-4 transition hover:decoration-ink"
      >
        by {recipe.user.firstName} {recipe.user.lastName}
      </Link>
    );
  }
  if (!recipe.sourceName) return null;
  if (!recipe.sourceUrl) return <span className="text-[15px] text-ink-soft">From {recipe.sourceName}</span>;
  return (
    <a
      href={recipe.sourceUrl}
      target="_blank"
      rel="noreferrer"
      className="inline-flex items-center gap-1 text-[15px] text-ink underline decoration-rule underline-offset-4 transition hover:decoration-ink"
    >
      From {recipe.sourceName}
      <ExternalLink aria-hidden="true" className="size-3.5 text-ink-soft" />
    </a>
  );
}
