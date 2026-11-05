import { Pill } from '@/components/ui';
import type { NutritionEstimate as Estimate } from '@/lib/types';
import { useRefreshWhilePending } from './useRefreshWhilePending';

/** The AI's calorie estimate for a recipe: the numbers when ready, a waiting line while pending, nothing otherwise. */
export function NutritionEstimate({
  estimate,
  queryKey,
}: {
  estimate: Estimate | null | undefined;
  queryKey: readonly unknown[];
}) {
  const waiting = useRefreshWhilePending(estimate?.status, queryKey);
  const ready = estimate?.status === 'ready' ? estimate : null;
  if (!waiting && !ready) return null;

  return (
    <div aria-live="polite" aria-atomic="true" className="space-y-1.5">
      {waiting && <p className="text-[13px] text-ink-soft">Working out calories...</p>}
      {ready && (
        <>
          <p className="text-[13px] text-ink-soft">AI estimate, per serving</p>
          <div className="flex flex-wrap gap-1.5">
            {ready.calories !== null && <Pill className="py-1 tabular-nums">{`about ${Math.round(ready.calories)} kcal`}</Pill>}
            {ready.proteinGrams !== null && <Pill className="py-1 tabular-nums">{`${Math.round(ready.proteinGrams)} g protein`}</Pill>}
            {ready.fatGrams !== null && <Pill className="py-1 tabular-nums">{`${Math.round(ready.fatGrams)} g fat`}</Pill>}
            {ready.carbsGrams !== null && <Pill className="py-1 tabular-nums">{`${Math.round(ready.carbsGrams)} g carbs`}</Pill>}
          </div>
        </>
      )}
    </div>
  );
}
