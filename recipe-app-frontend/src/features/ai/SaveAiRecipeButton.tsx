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
      className={`aria-disabled:cursor-wait ${saved ? '!border-ink !bg-tint' : ''}`}
    >
      <Bookmark aria-hidden="true" className={`size-4 ${saved ? 'fill-accent' : 'fill-transparent'}`} strokeWidth={2} />
      {saved ? 'Saved' : 'Save'}
    </Button>
  );
}
