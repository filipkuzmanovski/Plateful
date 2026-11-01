import { useEffect, useId, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { ImagePlus, LoaderCircle } from 'lucide-react';
import { importPost } from '@/api/ai';
import { ApiError } from '@/api/client';
import { Button } from '@/components/ui';
import { FormError } from '@/features/auth/AuthCard';
import { PhotoUnreadableError, shrinkPhoto } from '@/lib/shrinkPhoto';
import { aiErrorMessage, PHOTO_UNREADABLE, POST_GENERATION_FAILED, SCREENSHOT_INVALID } from './aiMessages';
import { CookResult } from './CookResult';
import { PhotoThumbs, usePhotoPicks } from './PhotoPicks';

const MAX_CAPTION = 2200;
const MAX_SCREENSHOTS = 2;
const ACCEPT = 'image/jpeg,image/png,image/webp,image/heic,image/heif';

const legendClass = 'mb-2 block text-[13px] font-semibold text-olive';

const count = (n: number) => n.toLocaleString('en-US');

/** What an import sends: the trimmed caption (null when blank) and the shrunk screenshots. */
interface Parts { caption: string | null; screenshots: Blob[]; }

/** Make recipe sends the picked files, shrunk first; Generate another re-sends the last parts as they were. */
type ImportRequest = { caption: string | null; files: File[] } | { again: Parts };

function errorMessage(err: unknown): string {
  if (err instanceof PhotoUnreadableError) return PHOTO_UNREADABLE;
  if (err instanceof ApiError && err.code === 'invalid_photo') return SCREENSHOT_INVALID;
  if (err instanceof ApiError && err.code === 'generation_failed') return POST_GENERATION_FAILED;
  return aiErrorMessage(err);
}

/**
 * The "From a post" panel on the Cook page: a pasted caption and/or up to 2 screenshots become a recipe made for
 * the user. The caption is never cut off: past 2,200 characters the panel says so and Make recipe is disabled.
 * The daily count is shared with the ingredients tab, so the page owns it (`left`, `limitReached`).
 */
export function PostImport({
  left, limitReached, onRemaining, onLimit,
}: {
  left: number | null;
  limitReached: boolean;
  onRemaining: (n: number) => void;
  onLimit: () => void;
}) {
  const [caption, setCaption] = useState('');
  const { photos, tooMany, add, remove, dismissTooMany } = usePhotoPicks(MAX_SCREENSHOTS);
  const inputRef = useRef<HTMLInputElement>(null);
  const lastParts = useRef<Parts | null>(null); // for Generate another, kept until the page is left
  const resultRef = useRef<HTMLDivElement>(null);
  const captionId = useId();
  const counterId = `${captionId}-count`;
  const overId = `${captionId}-over`;

  const make = useMutation({
    mutationFn: async (request: ImportRequest) => {
      let parts: Parts;
      if ('again' in request) {
        parts = request.again;
      } else {
        // One at a time: each decoded bitmap can be ~100 MB, and a phone may kill the tab if several are alive at once.
        const screenshots: Blob[] = [];
        for (const file of request.files) screenshots.push(await shrinkPhoto(file));
        parts = { caption: request.caption, screenshots };
      }
      lastParts.current = parts;
      return importPost(parts.caption, parts.screenshots);
    },
    onMutate: () => dismissTooMany(),
    onSuccess: (res) => onRemaining(res.remainingToday),
    onError: (err) => {
      if (err instanceof ApiError && err.code === 'daily_limit') onLimit();
    },
  });

  const result = make.data?.recipe;
  useEffect(() => {
    if (result) resultRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  }, [result?.id]);

  /** An error is about the last attempt; once the user changes the input it no longer applies. */
  function clearStaleError() {
    if (make.isError) make.reset();
  }

  function onPick(e: ChangeEvent<HTMLInputElement>) {
    const picked = Array.from(e.target.files ?? []);
    e.target.value = ''; // so choosing the same file again still fires a change
    if (picked.length > 0) {
      clearStaleError();
      add(picked);
    }
  }

  function onRemove(id: number) {
    clearStaleError();
    remove(id);
  }

  const busy = make.isPending;
  const length = caption.trimEnd().length; // the server trims, so trailing spaces and line breaks don't count
  const overLimit = length > MAX_CAPTION;
  const hasInput = caption.trim().length > 0 || photos.length > 0;
  const exhausted = limitReached || left === 0;
  const canMake = hasInput && !overLimit && !busy && !exhausted;

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!canMake) return;
    make.mutate({ caption: caption.trim() || null, files: photos.map((p) => p.file) });
  }

  return (
    <>
      <form onSubmit={onSubmit} className="space-y-7">
        <div>
          <label htmlFor={captionId} className={legendClass}>Paste the post's caption</label>
          <textarea
            id={captionId}
            value={caption}
            onChange={(e) => { clearStaleError(); setCaption(e.target.value); }}
            rows={6}
            aria-describedby={overLimit ? `${counterId} ${overId}` : counterId}
            aria-invalid={overLimit || undefined}
            className="w-full resize-y rounded-xl border border-sand bg-paper px-3.5 py-2.5 text-sm leading-relaxed text-olive shadow-[inset_0_1px_2px_rgb(47_58_43/0.04)] transition placeholder:text-olive/70 hover:border-olive/30 focus:border-olive/60 focus:ring-4 focus:ring-saffron/20 focus:outline-none aria-[invalid=true]:border-danger/60"
          />
          <div className="mt-1.5 flex items-start justify-between gap-3">
            {overLimit
              ? <span id={overId} className="text-xs font-medium text-danger">Shorten the caption to 2,200 characters.</span>
              : <span />}
            <span
              id={counterId}
              className={`shrink-0 text-xs tabular-nums ${overLimit ? 'font-semibold text-danger' : 'text-olive/70'}`}
            >
              {count(length)} / {count(MAX_CAPTION)}
            </span>
          </div>
        </div>

        <div>
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
          <Button type="button" variant="outline" disabled={busy} onClick={() => inputRef.current?.click()}>
            <ImagePlus aria-hidden="true" className="size-4" strokeWidth={2} />
            Add screenshots
          </Button>
          {tooMany && (
            <p className="mt-2 text-xs font-medium text-olive">Choose up to {MAX_SCREENSHOTS} screenshots.</p>
          )}
          <PhotoThumbs photos={photos} noun="screenshot" busy={busy} onRemove={onRemove} />
          <p className="mt-3 text-xs leading-relaxed text-olive/70">
            Tip: a screenshot of the recipe slide works best. Open the full caption (tap 'more') before you copy it.
          </p>
        </div>

        {make.isError && <FormError>{errorMessage(make.error)}</FormError>}

        <div>
          <Button type="submit" disabled={!canMake} className="w-full sm:w-auto sm:min-w-44">
            {busy && <LoaderCircle aria-hidden="true" className="size-4 animate-spin" />}
            Make recipe
          </Button>
          {left !== null && <p className="mt-2 text-xs text-olive/70">{left} left today</p>}
          <p className="mt-3 text-xs leading-relaxed text-olive/70">
            Screenshots go to Groq only and are never stored. The caption may go to Groq or Google Gemini. Nothing
            about the post is kept.
          </p>
        </div>
      </form>

      {/* Always mounted: a live region that appears already holding text is often not announced. */}
      <div role="status" aria-live="polite" className={`text-sm font-medium text-olive ${busy ? 'mt-10' : ''}`}>
        {busy && <p>Reading the post and writing your recipe. This can take up to a minute.</p>}
      </div>
      {busy && (
        <div aria-hidden="true" className="mt-4 animate-pulse space-y-4 rounded-card bg-paper p-6 shadow-card ring-1 ring-sand/70">
          <div className="h-7 w-2/3 rounded-lg bg-sand/70" />
          <div className="h-4 w-full rounded bg-sand/60" />
          <div className="h-4 w-5/6 rounded bg-sand/60" />
          <div className="h-24 rounded-xl bg-sand/50" />
        </div>
      )}

      {result && (
        <div ref={resultRef} className="mt-12 scroll-mt-24">
          <CookResult
            key={result.id}
            recipe={result}
            busy={busy}
            onAgain={() => { if (lastParts.current && !exhausted) make.mutate({ again: lastParts.current }); }}
          />
        </div>
      )}
    </>
  );
}
