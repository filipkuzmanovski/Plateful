import { Link } from 'react-router';
import { Clock, UtensilsCrossed } from 'lucide-react';
import type { RecipeDisplay } from '@/lib/types';
import { StarDisplay } from './StarDisplay';
import { Pill } from './ui';
import { BookmarkButton } from '@/features/saved/BookmarkButton';

export function RecipeCard({ recipe }: { recipe: RecipeDisplay }) {
  return (
    // The wrapper carries the hover lift so the bookmark (a sibling of the link, never inside it) rises with the card.
    <div className="group relative flex transition duration-300 ease-out hover:-translate-y-1">
      <Link
        to={`/recipes/${recipe.id}`}
        className="flex flex-1 flex-col overflow-hidden rounded-card bg-paper shadow-card ring-1 ring-sand/70 transition duration-300 ease-out group-hover:shadow-lift group-hover:ring-sand"
      >
        <div className="aspect-[4/3] overflow-hidden bg-sand">
          {recipe.image ? (
            <img
              src={recipe.image}
              alt=""
              loading="lazy"
              className="size-full object-cover transition-transform duration-500 ease-out group-hover:scale-[1.04]"
            />
          ) : (
            <div className="flex size-full items-center justify-center bg-sand text-olive/30">
              <UtensilsCrossed aria-hidden="true" className="size-10" strokeWidth={1.25} />
            </div>
          )}
        </div>
        <div className="flex flex-1 flex-col gap-2.5 px-4 pt-3.5 pb-4">
          <h3 className="line-clamp-2 font-serif text-lg leading-snug font-semibold text-olive transition-colors group-hover:text-terracotta">
            {recipe.title}
          </h3>
          <div className="mt-auto flex flex-wrap items-center gap-x-3 gap-y-1">
            <StarDisplay average={recipe.averageRating} count={recipe.ratingCount} />
            {recipe.readyInMinutes !== null && (
              <span className="inline-flex items-center gap-1 text-sm text-olive/70 tabular-nums">
                <Clock aria-hidden="true" className="size-3.5" strokeWidth={1.75} />
                {recipe.readyInMinutes} min
              </span>
            )}
          </div>
          {recipe.cuisines.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {recipe.cuisines.map((c) => (
                <Pill key={c} tone="saffron" className="capitalize">{c}</Pill>
              ))}
            </div>
          )}
        </div>
      </Link>
      <BookmarkButton recipeId={recipe.id} title={recipe.title} />
    </div>
  );
}
