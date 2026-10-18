import { useEffect, useId, useRef, useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { LoaderCircle, Play, RefreshCw, Trash2, Video, X } from 'lucide-react';
import {
  ACCEPTED_VIDEO_TYPES,
  MAX_VIDEO_BYTES,
  MAX_VIDEO_SECONDS,
  deleteVideoFiles,
  publicVideoUrl,
  uploadPoster,
  uploadVideo,
} from '@/api/videos';
import { capturePoster, probeVideo } from '@/lib/videoProbe';
import { formatClock } from '@/lib/formatClock';
import { Button } from '@/components/ui';
import type { RecipeVideoInput } from '@/lib/types';

const TYPE_MESSAGE = 'Videos must be MP4, WebM or MOV, up to 50 MB.';
const UNPLAYABLE_MESSAGE = "This video can't be played in most browsers. Please export it as MP4 and try again.";
const TOO_LONG_MESSAGE = `Videos can be up to ${MAX_VIDEO_SECONDS} seconds long.`;
const UPLOAD_FAILED_MESSAGE = "Couldn't upload the video. Please try again.";

/** The poster is taken one second in; capturePoster clamps it for clips shorter than two seconds. */
const POSTER_AT_SECONDS = 1;

// jsdom and very old browsers lack these; a missing preview just means the public URL is used.
const makePreviewUrl = (file: File) =>
  typeof URL.createObjectURL === 'function' ? URL.createObjectURL(file) : '';
const revokePreviewUrl = (url: string) => {
  if (url && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url);
};

/** A clip picked in this session: playing it back needs no download from storage. */
interface LocalClip {
  path: string;
  url: string;
}

function PreviewDialog({ src, poster, onClose }: { src: string; poster: string; onClose: () => void }) {
  return (
    <Dialog.Root open onOpenChange={(o) => !o && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 animate-overlay-in bg-olive/35 backdrop-blur-[2px]" />
        <Dialog.Content
          aria-describedby={undefined}
          className="fixed top-1/2 left-1/2 z-50 w-[min(40rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 animate-dialog-in rounded-card bg-paper p-4 shadow-lift ring-1 ring-sand sm:p-5"
        >
          <div className="mb-3 flex items-center justify-between gap-3">
            <Dialog.Title className="font-serif text-xl font-semibold tracking-tight text-olive">
              Video preview
            </Dialog.Title>
            <Dialog.Close asChild>
              <Button type="button" variant="ghost" aria-label="Close" className="!size-9 !p-0">
                <X aria-hidden="true" className="size-4" />
              </Button>
            </Dialog.Close>
          </div>
          <video controls autoPlay playsInline src={src} poster={poster} className="max-h-[70vh] w-full rounded-xl bg-olive" />
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/**
 * Optional single-video field. `value` is the uploaded clip and poster (storage paths) plus the
 * duration in whole seconds, rounded up. A new clip reaches `onChange` only once both files are
 * uploaded; `onBusyChange(true)` covers everything from picking the file until then. Files this
 * field replaces or removes are not deleted here: the form deletes them after a successful save.
 */
export function VideoPicker({
  value,
  onChange,
  onBusyChange,
  userId,
}: {
  value: RecipeVideoInput | null;
  onChange: (video: RecipeVideoInput | null) => void;
  onBusyChange: (busy: boolean) => void;
  userId: string;
}) {
  const labelId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [local, setLocal] = useState<LocalClip | null>(null);

  const mounted = useRef(true);
  const localRef = useRef<LocalClip | null>(null);
  const onChangeRef = useRef(onChange);
  const onBusyChangeRef = useRef(onBusyChange);
  useEffect(() => {
    onChangeRef.current = onChange;
    onBusyChangeRef.current = onBusyChange;
  });
  useEffect(() => {
    onBusyChangeRef.current(busy);
  }, [busy]);

  // A late upload result finds the field gone: it deletes its own files instead of reporting.
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      if (localRef.current) revokePreviewUrl(localRef.current.url);
    };
  }, []);

  function keepLocal(next: LocalClip | null) {
    if (localRef.current) revokePreviewUrl(localRef.current.url);
    localRef.current = next;
    setLocal(next);
  }

  async function onFile(file: File) {
    setMessage(null);
    if (!ACCEPTED_VIDEO_TYPES.includes(file.type) || file.size > MAX_VIDEO_BYTES) {
      setMessage(TYPE_MESSAGE);
      return;
    }
    setBusy(true);
    try {
      const probe = await probeVideo(file);
      if (!probe.playable) {
        setMessage(UNPLAYABLE_MESSAGE);
        return;
      }
      const durationSeconds = Math.ceil(probe.durationSeconds);
      if (durationSeconds > MAX_VIDEO_SECONDS) {
        setMessage(TOO_LONG_MESSAGE);
        return;
      }
      let posterBlob: Blob;
      try {
        posterBlob = await capturePoster(file, POSTER_AT_SECONDS);
      } catch {
        setMessage(UNPLAYABLE_MESSAGE); // a clip whose frames can't be read isn't playable here either
        return;
      }

      const [clip, poster] = await Promise.allSettled([uploadVideo(userId, file), uploadPoster(userId, posterBlob)]);
      if (clip.status === 'rejected' || poster.status === 'rejected') {
        // Never leave one half of the pair behind.
        const orphans = [clip, poster].flatMap((r) => (r.status === 'fulfilled' ? [r.value] : []));
        void deleteVideoFiles(orphans);
        setMessage(UPLOAD_FAILED_MESSAGE);
        return;
      }
      if (!mounted.current) {
        void deleteVideoFiles([clip.value, poster.value]);
        return;
      }
      keepLocal({ path: clip.value, url: makePreviewUrl(file) });
      onChangeRef.current({ path: clip.value, posterPath: poster.value, durationSeconds });
    } catch {
      if (mounted.current) setMessage(UPLOAD_FAILED_MESSAGE);
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  function remove() {
    keepLocal(null);
    setMessage(null);
    onChangeRef.current(null);
  }

  const previewSrc = value ? (local?.path === value.path && local.url ? local.url : publicVideoUrl(value.path)) : '';

  return (
    <section aria-labelledby={labelId}>
      <span id={labelId} className="mb-2 block text-[13px] font-semibold text-olive">
        Video (optional)
      </span>

      {value && (
        <div className="mb-3 flex flex-wrap items-center gap-3">
          <div className="relative h-20 w-32 shrink-0 overflow-hidden rounded-xl bg-sand ring-1 ring-sand">
            <img src={publicVideoUrl(value.posterPath)} alt="Video poster" className="size-full object-cover" />
            <span className="absolute right-1.5 bottom-1.5 rounded-full bg-olive/85 px-2 py-0.5 text-[11px] font-semibold text-cream tabular-nums">
              {formatClock(value.durationSeconds)}
            </span>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="outline" onClick={() => setPreviewing(true)}>
              <Play aria-hidden="true" className="size-4" />
              Play
            </Button>
            <Button type="button" variant="outline" disabled={busy} onClick={() => inputRef.current?.click()}>
              <RefreshCw aria-hidden="true" className="size-4" />
              Replace
            </Button>
            <Button type="button" variant="ghost" disabled={busy} onClick={remove}>
              <Trash2 aria-hidden="true" className="size-4" />
              Remove
            </Button>
          </div>
        </div>
      )}

      {!value && (
        <Button type="button" variant="outline" disabled={busy} onClick={() => inputRef.current?.click()}>
          <Video aria-hidden="true" className="size-4" />
          Add video
        </Button>
      )}
      <input
        ref={inputRef}
        type="file"
        hidden
        disabled={busy}
        aria-label="Add video"
        accept={ACCEPTED_VIDEO_TYPES.join(',')}
        onChange={(e) => {
          const file = e.target.files?.[0];
          e.target.value = ''; // so choosing the same file again still fires change
          if (file) void onFile(file);
        }}
      />

      {busy && (
        <p role="status" className="mt-2 flex items-center gap-2 text-sm text-olive/70">
          <LoaderCircle aria-hidden="true" className="size-4 animate-spin text-saffron" />
          Uploading video…
        </p>
      )}
      <p className="mt-2 text-xs text-olive/70">MP4, WebM or MOV, up to {MAX_VIDEO_SECONDS} seconds and 50 MB.</p>
      {message && (
        <p role="alert" className="mt-1.5 text-xs font-medium text-danger">
          {message}
        </p>
      )}

      {previewing && value && (
        <PreviewDialog src={previewSrc} poster={publicVideoUrl(value.posterPath)} onClose={() => setPreviewing(false)} />
      )}
    </section>
  );
}
