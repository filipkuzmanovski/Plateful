import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Bookmark } from 'lucide-react';
import { saveAiRecipe, unsaveAiRecipe } from '@/api/ai';
import { Button } from '@/components/ui';
import { aiErrorMessage } from './aiMessages';

/** "Save" / "Saved" for one AI recipe; clicking Saved removes it from the saved list. */
export function SaveAiRecipeButton({ id, saved }: { id: string; saved: boolean }) {
  const queryClient = useQueryClient();
  const toggle = useMutation({
    mutationFn: () => (saved ? unsaveAiRecipe(id) : saveAiRecipe(id)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['aiRecipe', id] });
      void queryClient.invalidateQueries({ queryKey: ['aiSaved'] });
    },
    onError: (e) => toast.error(aiErrorMessage(e)),
  });

  return (
    <Button
      type="button"
      variant="outline"
      aria-pressed={saved}
      aria-disabled={toggle.isPending || undefined}
      onClick={() => { if (!toggle.isPending) toggle.mutate(); }}
      className={`!px-4 !py-2 aria-disabled:cursor-wait ${saved ? '!border-saffron/60 !bg-saffron/15 hover:!bg-saffron/25' : ''}`}
    >
      <Bookmark aria-hidden="true" className={`size-3.5 ${saved ? 'fill-saffron' : 'fill-transparent'}`} strokeWidth={2} />
      {saved ? 'Saved' : 'Save'}
    </Button>
  );
}
