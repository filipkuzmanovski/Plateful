import { useEffect, useId, useRef, useState } from 'react';
import { ChevronLeft, ChevronRight, ImagePlus, LoaderCircle, X } from 'lucide-react';
import { ACCEPTED_TYPES, MAX_PHOTOS, publicPhotoUrl, uploadPhoto } from '@/api/photos';
import { prepareImage } from '@/lib/resizeImage';
import { Button, Pill } from '@/components/ui';

const TYPE_MESSAGE = 'Only JPEG, PNG or WebP photos.';
const LIMIT_MESSAGE = `You can add up to ${MAX_PHOTOS} photos.`;

/** A photo that is still being prepared or uploaded, or that failed. Finished photos live in `value`. */
interface PendingTile {
  id: number;
  file: File;
  previewUrl: string;
  status: 'pending' | 'error';
  message?: string;
}

// jsdom and very old browsers lack these; a missing preview just shows an empty tile.
const makePreviewUrl = (file: File) =>
  typeof URL.createObjectURL === 'function' ? URL.createObjectURL(file) : '';
const revokePreviewUrl = (url: string) => {
  if (url && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url);
};

const iconButton =
  'flex size-7 items-center justify-center rounded-full bg-paper/90 text-olive shadow-button transition hover:bg-paper focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-saffron disabled:pointer-events-none disabled:opacity-40';

/**
 * Multi-photo picker. `value` is the ordered list of uploaded storage paths (position 0 is the
 * cover). Photos still uploading, or that failed, stay in local state after the finished ones and
 * only reach `onChange` once their upload succeeds. `onBusyChange(true)` while any is unfinished.
 */
export function PhotoPicker({
  value,
  onChange,
  userId,
  onBusyChange,
}: {
  value: string[];
  onChange: (paths: string[]) => void;
  userId: string;
  onBusyChange: (busy: boolean) => void;
}) {
  const labelId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const [tiles, setTiles] = useState<PendingTile[]>([]);
  const [notices, setNotices] = useState<string[]>([]);

  // Refs let async upload callbacks see the latest state: several uploads can finish between renders,
  // and the parent may not have re-rendered with the previous result yet.
  const tilesRef = useRef<PendingTile[]>([]);
  const valueRef = useRef(value);
  const onChangeRef = useRef(onChange);
  const nextId = useRef(0);

  useEffect(() => {
    onChangeRef.current = onChange;
  });
  useEffect(() => {
    valueRef.current = value;
  }, [value]);

  const busy = tiles.length > 0;
  const onBusyChangeRef = useRef(onBusyChange);
  useEffect(() => {
    onBusyChangeRef.current = onBusyChange;
  });
  useEffect(() => {
    onBusyChangeRef.current(busy);
  }, [busy]);

  // Unmounting abandons any in-flight tile: its late result finds no tile and is ignored.
  useEffect(
    () => () => {
      tilesRef.current.forEach((t) => revokePreviewUrl(t.previewUrl));
      tilesRef.current = [];
    },
    [],
  );

  function updateTiles(fn: (tiles: PendingTile[]) => PendingTile[]) {
    tilesRef.current = fn(tilesRef.current);
    setTiles(tilesRef.current);
  }

  function emit(next: string[]) {
    valueRef.current = next;
    onChangeRef.current(next);
  }

  async function process(id: number, file: File) {
    try {
      const { blob, ext } = await prepareImage(file);
      const path = await uploadPhoto(userId, blob, ext);
      const tile = tilesRef.current.find((t) => t.id === id);
      if (!tile) return; // removed (or unmounted) while uploading: drop the result
      revokePreviewUrl(tile.previewUrl);
      updateTiles((all) => all.filter((t) => t.id !== id));
      emit([...valueRef.current, path]);
    } catch (e) {
      if (!tilesRef.current.some((t) => t.id === id)) return;
      const message = e instanceof Error && e.message ? e.message : 'Upload failed';
      updateTiles((all) => all.map((t) => (t.id === id ? { ...t, status: 'error', message } : t)));
    }
  }

  async function onFiles(files: File[]) {
    const accepted = files.filter((f) => ACCEPTED_TYPES.includes(f.type));
    const free = Math.max(MAX_PHOTOS - valueRef.current.length - tilesRef.current.length, 0);
    const toAdd = accepted.slice(0, free);

    const next: string[] = [];
    if (accepted.length < files.length) next.push(TYPE_MESSAGE);
    if (accepted.length > free) next.push(LIMIT_MESSAGE);
    setNotices(next);

    const added = toAdd.map<PendingTile>((file) => ({
      id: nextId.current++,
      file,
      previewUrl: makePreviewUrl(file),
      status: 'pending',
    }));
    if (added.length === 0) return;
    updateTiles((all) => [...all, ...added]);
    // One at a time, so photos land in the order they were selected.
    for (const t of added) await process(t.id, t.file);
  }

  function retry(tile: PendingTile) {
    updateTiles((all) => all.map((t) => (t.id === tile.id ? { ...t, status: 'pending', message: undefined } : t)));
    void process(tile.id, tile.file);
  }

  function removePending(tile: PendingTile) {
    revokePreviewUrl(tile.previewUrl);
    updateTiles((all) => all.filter((t) => t.id !== tile.id));
    setNotices([]);
  }

  function removeFinished(index: number) {
    emit(valueRef.current.filter((_, i) => i !== index));
    setNotices([]);
  }

  function move(index: number, delta: -1 | 1) {
    const next = [...valueRef.current];
    const target = index + delta;
    if (target < 0 || target >= next.length) return;
    [next[index], next[target]] = [next[target], next[index]];
    emit(next);
  }

  const full = value.length + tiles.length >= MAX_PHOTOS;

  return (
    <section aria-labelledby={labelId}>
      <span id={labelId} className="mb-2 block text-[13px] font-semibold text-olive">
        Photos
      </span>

      {(value.length > 0 || tiles.length > 0) && (
        <ul className="mb-3 grid grid-cols-3 gap-2.5 sm:grid-cols-6">
          {value.map((path, i) => (
            <li key={path} className="relative aspect-square overflow-hidden rounded-xl bg-sand ring-1 ring-sand">
              <img src={publicPhotoUrl(path)} alt={`Photo ${i + 1}`} className="size-full object-cover" />
              {i === 0 && (
                <span className="absolute top-1.5 left-1.5">
                  <Pill tone="olive" className="bg-paper/90 shadow-button">
                    Cover
                  </Pill>
                </span>
              )}
              <button
                type="button"
                aria-label={`Remove photo ${i + 1}`}
                onClick={() => removeFinished(i)}
                className={`${iconButton} absolute top-1.5 right-1.5`}
              >
                <X aria-hidden="true" className="size-4" strokeWidth={2.5} />
              </button>
              <div className="absolute inset-x-1.5 bottom-1.5 flex justify-between">
                <button
                  type="button"
                  aria-label={`Move photo ${i + 1} left`}
                  disabled={i === 0}
                  onClick={() => move(i, -1)}
                  className={iconButton}
                >
                  <ChevronLeft aria-hidden="true" className="size-4" strokeWidth={2.5} />
                </button>
                <button
                  type="button"
                  aria-label={`Move photo ${i + 1} right`}
                  disabled={i === value.length - 1}
                  onClick={() => move(i, 1)}
                  className={iconButton}
                >
                  <ChevronRight aria-hidden="true" className="size-4" strokeWidth={2.5} />
                </button>
              </div>
            </li>
          ))}

          {tiles.map((tile, i) => {
            const n = value.length + i + 1;
            return (
              <li key={tile.id} className="relative aspect-square overflow-hidden rounded-xl bg-sand ring-1 ring-sand">
                {tile.status === 'pending' ? (
                  <>
                    {tile.previewUrl && (
                      <img src={tile.previewUrl} alt={`Photo ${n}`} className="size-full object-cover opacity-60" />
                    )}
                    <span
                      role="status"
                      aria-label={`Uploading photo ${n}`}
                      className="absolute inset-0 flex items-center justify-center"
                    >
                      <LoaderCircle aria-hidden="true" className="size-6 animate-spin text-saffron" />
                    </span>
                    <button
                      type="button"
                      aria-label={`Remove photo ${n}`}
                      onClick={() => removePending(tile)}
                      className={`${iconButton} absolute top-1.5 right-1.5`}
                    >
                      <X aria-hidden="true" className="size-4" strokeWidth={2.5} />
                    </button>
                  </>
                ) : (
                  <div className="flex size-full flex-col items-center justify-center gap-1.5 bg-cream p-1.5 text-center">
                    <p role="alert" className="line-clamp-3 text-[11px] leading-tight font-medium text-danger">
                      {tile.message}
                    </p>
                    <div className="flex flex-wrap justify-center gap-1">
                      <Button type="button" variant="outline" className="!px-2.5 !py-0.5 !text-xs" onClick={() => retry(tile)}>
                        Retry
                      </Button>
                      <Button type="button" variant="ghost" className="!px-2.5 !py-0.5 !text-xs" onClick={() => removePending(tile)}>
                        Remove
                      </Button>
                    </div>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}

      <Button type="button" variant="outline" disabled={full} onClick={() => inputRef.current?.click()}>
        <ImagePlus aria-hidden="true" className="size-4" />
        Add photos
      </Button>
      <input
        ref={inputRef}
        type="file"
        multiple
        hidden
        disabled={full}
        aria-label="Add photos"
        accept={ACCEPTED_TYPES.join(',')}
        onChange={(e) => {
          const files = Array.from(e.target.files ?? []);
          e.target.value = ''; // so choosing the same file again still fires change
          void onFiles(files);
        }}
      />

      <p className="mt-2 text-xs text-olive/70">
        Up to {MAX_PHOTOS} photos. The first one is the cover.
      </p>
      {notices.map((m) => (
        <p key={m} role="alert" className="mt-1.5 text-xs font-medium text-danger">
          {m}
        </p>
      ))}
    </section>
  );
}
