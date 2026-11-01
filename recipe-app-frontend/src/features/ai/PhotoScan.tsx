import { useRef, useState, type ChangeEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Camera, LoaderCircle } from 'lucide-react';
import { aiUsageQueryKey, getAiUsage, scanPhotos } from '@/api/ai';
import { ApiError } from '@/api/client';
import { useAuth } from '@/auth/AuthProvider';
import { Button } from '@/components/ui';
import { FormError } from '@/features/auth/AuthCard';
import { PhotoUnreadableError, shrinkPhoto } from '@/lib/shrinkPhoto';
import type { ScanResult } from '@/lib/types';
import { aiErrorMessage, PHOTO_UNREADABLE } from './aiMessages';
import { leftOutLine, mergeIngredients } from './mergeIngredients';
import { PhotoThumbs, usePhotoPicks } from './PhotoPicks';

const MAX_PHOTOS = 3;
const ACCEPT = 'image/jpeg,image/png,image/webp,image/heic,image/heif';

/** What the last scan did to the chips, shown under the panel until the next scan. */
interface Outcome { found: number; didntFit: string[]; leftOut: string | null; }

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/**
 * The "From a photo" panel on the Cook page: up to 3 photos are picked, shrunk in the browser, scanned,
 * and the ingredients found are merged into the chips through `onFound`. Photos are cleared after a scan.
 */
export function PhotoScan({
  current, max, onFound,
}: { current: string[]; max: number; onFound: (merged: string[]) => void }) {
  const { profile } = useAuth();
  const userId = profile?.id;
  const queryClient = useQueryClient();

  const { photos, tooMany, add, remove, clear, dismissTooMany } = usePhotoPicks(MAX_PHOTOS);
  const [remaining, setRemaining] = useState<number | null>(null); // the latest count the server gave us
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // The chips can change while a scan is running; merge into whatever is there when the reply arrives.
  const currentRef = useRef(current);
  currentRef.current = current;

  const usage = useQuery({ queryKey: aiUsageQueryKey(userId), queryFn: getAiUsage });
  const left = remaining ?? usage.data?.scansRemaining ?? null;
  const limitReached = left === 0;

  const scan = useMutation({
    mutationFn: async (files: File[]) => {
      // One at a time: each decoded bitmap can be ~100 MB, and a phone may kill the tab if three are alive at once.
      const blobs: Blob[] = [];
      for (const file of files) blobs.push(await shrinkPhoto(file));
      return scanPhotos(blobs);
    },
    onMutate: () => {
      setOutcome(null);
      dismissTooMany();
    },
    onSuccess: (res: ScanResult) => {
      setRemaining(res.remainingScans);
      void queryClient.invalidateQueries({ queryKey: aiUsageQueryKey(userId) });
      const { merged, didntFit } = mergeIngredients(currentRef.current, res.ingredients, max);
      if (merged.length !== currentRef.current.length) onFound(merged);
      setOutcome({ found: res.ingredients.length, didntFit, leftOut: leftOutLine(res.leftOut) });
      clear();
    },
    onError: (err) => {
      if (err instanceof ApiError && err.code === 'scan_limit') setRemaining(0);
    },
  });

  function onPick(e: ChangeEvent<HTMLInputElement>) {
    const picked = Array.from(e.target.files ?? []);
    e.target.value = ''; // so choosing the same file again still fires a change
    if (picked.length === 0) return;
    scan.reset(); // a fresh pick makes the last error stale
    add(picked);
  }

  function onRemove(id: number) {
    scan.reset(); // the error was about the photos as they were
    remove(id);
  }

  const busy = scan.isPending;
  const errorMessage = scan.isError
    ? (scan.error instanceof PhotoUnreadableError ? PHOTO_UNREADABLE : aiErrorMessage(scan.error))
    : null;
  const noFood = outcome !== null && outcome.found === 0 && outcome.leftOut === null;

  return (
    <section aria-labelledby="photo-scan-heading" className="rounded-2xl border border-sand bg-paper p-4 sm:p-5">
      <h2 id="photo-scan-heading" className="mb-3 text-[13px] font-semibold text-olive">From a photo</h2>

      <input
        ref={inputRef}
        type="file"
        accept={ACCEPT}
        multiple
        hidden
        tabIndex={-1}
        aria-hidden="true"
        onChange={onPick}
      />

      <div className="flex flex-wrap items-center gap-2.5">
        <Button type="button" variant="outline" disabled={busy} onClick={() => inputRef.current?.click()}>
          <Camera aria-hidden="true" className="size-4" strokeWidth={2} />
          Take or choose photos
        </Button>
      </div>

      {tooMany && <p className="mt-2 text-xs font-medium text-olive">Choose up to {MAX_PHOTOS} photos.</p>}

      <PhotoThumbs photos={photos} noun="photo" busy={busy} onRemove={onRemove} />

      <div className="mt-3">
        <Button
          type="button"
          disabled={photos.length === 0 || busy || limitReached}
          onClick={() => scan.mutate(photos.map((p) => p.file))}
        >
          {busy && <LoaderCircle aria-hidden="true" className="size-4 animate-spin" />}
          Scan photos
        </Button>
        {left !== null && (
          <p className="mt-2 text-xs text-olive/70">{plural(left, 'scan', 'scans')} left today</p>
        )}
      </div>

      {errorMessage && <div className="mt-3"><FormError>{errorMessage}</FormError></div>}

      {/* Always mounted: a live region that appears already holding text is often not announced. */}
      <div role="status" aria-live="polite" className={`space-y-1.5 text-sm text-olive ${busy || outcome ? 'mt-3' : ''}`}>
        {busy && <p className="font-medium">Looking at your photos. This can take up to 30 seconds.</p>}
        {outcome && !busy && (
          <>
            {outcome.found > 0 && (
              <p>
                Found {plural(outcome.found, 'ingredient', 'ingredients')}. Check them: remove anything wrong and
                add anything missed.
              </p>
            )}
            {outcome.didntFit.length > 0 && (
              <p>{outcome.didntFit.length} more didn't fit ({max} max): {outcome.didntFit.join(', ')}.</p>
            )}
            {outcome.leftOut && <p>{outcome.leftOut}</p>}
            {noFood && <p>No food spotted. Try a closer, brighter photo, or type your ingredients.</p>}
          </>
        )}
      </div>

      <p className="mt-3 text-xs leading-relaxed text-olive/70">
        Photos go to Groq only to spot ingredients and are never stored. Keep people and papers out of the shot.
      </p>
    </section>
  );
}
