import { Link } from 'react-router';
import { Clock, UtensilsCrossed, Video } from 'lucide-react';
import type { RecipeDisplay } from '@/lib/types';
import { StarDisplay } from './StarDisplay';
import { Pill } from './ui';
import { CARD_IMAGE_SIZES, recipeImageSrcSet } from '@/lib/recipeImage';
import { BookmarkButton } from '@/features/saved/BookmarkButton';
import { StatusBadge } from '@/features/review/StatusBadge';

export function RecipeCard({ recipe }: { recipe: RecipeDisplay }) {
  return (
    // No container: the photo and the text sit straight on the page. The bookmark is a sibling of the
    // link (never inside it), so the link is the `group` and the bookmark only needs the wrapper to anchor to.
    <div className="relative flex">
      <Link to={`/recipes/${recipe.id}`} className="group flex flex-1 flex-col gap-3">
        <div className="relative aspect-[4/3] overflow-hidden rounded-md bg-tint">
          {recipe.image ? (
            <img
              src={recipe.image}
              srcSet={recipeImageSrcSet(recipe.image)}
              sizes={CARD_IMAGE_SIZES}
              alt=""
              loading="lazy"
              className="size-full object-cover"
            />
          ) : (
            <div className="flex size-full items-center justify-center bg-tint text-ink-soft">
              <UtensilsCrossed aria-hidden="true" className="size-10" strokeWidth={1.25} />
            </div>
          )}
          <span
            aria-hidden="true"
            className="absolute inset-0 bg-ink/0 transition-colors duration-200 group-hover:bg-ink/6 group-focus-visible:bg-ink/6"
          />
          {recipe.video && (
            // Top left; the bookmark owns the top right, and the review status badge sits in the text below.
            <span className="absolute top-3 left-3 inline-flex items-center gap-1 rounded-full bg-ink/85 px-2.5 py-1 text-[13px] font-semibold text-on-ink">
              <Video aria-hidden="true" className="size-3.5" strokeWidth={2} />
              Video
            </span>
          )}
        </div>
        <div className="flex flex-1 flex-col gap-2">
          <h3 className="line-clamp-2 font-serif text-base leading-snug font-semibold tracking-[-0.015em] break-words text-ink decoration-1 underline-offset-4 group-hover:underline group-focus-visible:underline sm:text-lg">
            {recipe.title}
          </h3>
          <div className="mt-auto flex flex-wrap items-center gap-x-3 gap-y-1 text-[13px] text-ink-soft">
            <StarDisplay average={recipe.averageRating} count={recipe.ratingCount} />
            {recipe.readyInMinutes !== null && (
              <span className="inline-flex items-center gap-1 tabular-nums">
                <Clock aria-hidden="true" className="size-3.5" strokeWidth={1.75} />
                {recipe.readyInMinutes} min
              </span>
            )}
          </div>
          {recipe.review && recipe.review.status !== 'approved' && (
            <div><StatusBadge status={recipe.review.status} /></div>
          )}
          {recipe.cuisines.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {recipe.cuisines.map((c) => (
                <Pill key={c} tone="accent" className="capitalize">{c}</Pill>
              ))}
            </div>
          )}
        </div>
      </Link>
      <BookmarkButton recipeId={recipe.id} title={recipe.title} />
    </div>
  );
}
