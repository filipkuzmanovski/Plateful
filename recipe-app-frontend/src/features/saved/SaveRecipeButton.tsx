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
      className={`!px-4 !py-2 aria-disabled:cursor-wait ${saved ? '!border-saffron/60 !bg-saffron/15 hover:!bg-saffron/25' : ''}`}
    >
      <Bookmark
        aria-hidden="true"
        className={`size-3.5 ${saved ? 'fill-saffron' : 'fill-transparent'}`}
        strokeWidth={2}
      />
      {saved ? 'Saved' : 'Save'}
    </Button>
  );
}
