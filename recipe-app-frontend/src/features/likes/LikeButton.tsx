import { Heart } from 'lucide-react';
import { Button } from '@/components/ui';
import { isLive } from '@/lib/live';
import type { RecipeDisplay } from '@/lib/types';
import { useLikeControl } from './useLikeControl';

/**
 * The public heart and count. "card" sits over a recipe card's photo, as a sibling of the card link and never
 * inside it; "page" sits with the actions on the recipe page. Only published recipes can be liked, so a recipe
 * the viewer sees hidden or rejected shows nothing.
 */
export function LikeButton({ recipe, variant }: { recipe: RecipeDisplay; variant: 'card' | 'page' }) {
  const { liked, disabled, pending, toggle } = useLikeControl(recipe.id);
  if (!isLive(recipe)) return null;

  const count = `${recipe.likeCount} ${recipe.likeCount === 1 ? 'like' : 'likes'}`;
  const label = `${liked ? 'Remove like from' : 'Like'} ${recipe.title}, ${count}`;

  if (variant === 'page') {
    return (
      <Button
        type="button"
        variant="outline"
        aria-label={label}
        aria-pressed={liked}
        disabled={disabled}
        aria-disabled={pending || undefined}
        onClick={toggle}
        className={`tabular-nums aria-disabled:cursor-wait ${liked ? '!border-ink !bg-tint hover:!bg-rule' : ''}`}
      >
        <Heart aria-hidden="true" className={`size-4 ${liked ? 'fill-accent' : 'fill-transparent'}`} strokeWidth={2} />
        {recipe.likeCount}
      </Button>
    );
  }

  return (
    <button
      type="button"
      aria-label={label}
      aria-pressed={liked}
      disabled={disabled}
      aria-disabled={pending || undefined}
      onClick={toggle}
      className="absolute top-3 left-3 z-10 inline-flex min-h-10 min-w-10 items-center justify-center gap-1.5 rounded-full bg-surface px-3 text-[13px] font-semibold text-ink tabular-nums transition-colors duration-200 ease-out hover:bg-tint disabled:opacity-60 aria-disabled:cursor-wait"
    >
      <Heart
        aria-hidden="true"
        className={`size-5 transition-colors ${liked ? 'fill-accent' : 'fill-transparent'}`}
        strokeWidth={1.75}
      />
      {recipe.likeCount}
    </button>
  );
}
