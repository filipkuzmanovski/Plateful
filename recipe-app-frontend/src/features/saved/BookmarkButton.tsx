import { Bookmark } from 'lucide-react';
import { useSaveControl } from './useSaveControl';

/** The round bookmark over a recipe card's photo. A sibling of the card link, never inside it. */
export function BookmarkButton({ recipeId, title }: { recipeId: string; title: string }) {
  const { saved, disabled, pending, toggle } = useSaveControl(recipeId);
  return (
    <button
      type="button"
      aria-label={saved ? `Remove ${title} from saved` : `Save ${title}`}
      aria-pressed={saved}
      disabled={disabled}
      aria-disabled={pending || undefined}
      onClick={toggle}
      className="absolute top-3 right-3 z-10 flex size-10 items-center justify-center rounded-full bg-surface text-ink transition-colors duration-200 ease-out hover:bg-tint disabled:opacity-60 aria-disabled:cursor-wait"
    >
      <Bookmark
        aria-hidden="true"
        className={`size-5 transition-colors ${saved ? 'fill-accent' : 'fill-transparent'}`}
        strokeWidth={1.75}
      />
    </button>
  );
}
