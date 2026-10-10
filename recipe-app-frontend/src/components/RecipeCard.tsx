import { Link } from 'react-router';
import type { RecipeDisplay } from '@/lib/types';
import { StarDisplay } from './StarDisplay';

export function RecipeCard({ recipe }: { recipe: RecipeDisplay }) {
  return (
    <Link
      to={`/recipes/${recipe.id}`}
      className="group block border border-hairline transition-shadow hover:shadow-md"
    >
      {recipe.image ? (
        <img src={recipe.image} alt="" className="h-44 w-full object-cover" />
      ) : (
        <div className="flex h-44 w-full items-center justify-center bg-hairline text-4xl">🍽</div>
      )}
      <div className="p-3">
        <h3 className="font-bold leading-tight group-hover:text-accent">{recipe.title}</h3>
        <div className="mt-1 flex items-center gap-2">
          <StarDisplay average={recipe.averageRating} count={recipe.ratingCount} />
          {recipe.readyInMinutes !== null && (
            <span className="text-xs tracking-wide text-muted uppercase">{recipe.readyInMinutes} min</span>
          )}
        </div>
        {recipe.cuisines.length > 0 && (
          <p className="mt-1 text-xs tracking-widest text-muted uppercase">{recipe.cuisines.join(' · ')}</p>
        )}
      </div>
    </Link>
  );
}
