import { Bookmark } from 'lucide-react';
import { Button } from '@/components/ui';
import { useSaveControl } from './useSaveControl';

/** "Save" / "Saved" on the recipe page. */
export function SaveRecipeButton({ recipeId }: { recipeId: string }) {
  const { saved, disabled, pending, toggle } = useSaveControl(recipeId);
  return (
    <Button
      type="button"
      variant="outline"
      aria-pressed={saved}
      disabled={disabled}
      aria-disabled={pending || undefined}
      onClick={toggle}
      className={`aria-disabled:cursor-wait ${saved ? '!border-ink !bg-tint' : ''}`}
    >
      <Bookmark
        aria-hidden="true"
        className={`size-4 ${saved ? 'fill-accent' : 'fill-transparent'}`}
        strokeWidth={2}
      />
      {saved ? 'Saved' : 'Save'}
    </Button>
  );
}
