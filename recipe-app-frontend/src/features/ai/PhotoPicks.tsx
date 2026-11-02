import { useEffect, useRef, useState } from 'react';
import { X } from 'lucide-react';

export interface Photo { id: number; file: File; url: string; }

/**
 * Photos picked in the browser, each with an object URL for its thumbnail. Keeps at most `max`: a pick past
 * that keeps the first ones and sets `tooMany`. The URLs are revoked on remove, on clear and on unmount.
 */
export function usePhotoPicks(max: number) {
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [tooMany, setTooMany] = useState(false);
  const nextId = useRef(0);

  // Object URLs outlive the component unless revoked.
  const photosRef = useRef(photos);
  photosRef.current = photos;
  useEffect(() => () => photosRef.current.forEach((p) => URL.revokeObjectURL(p.url)), []);

  function add(files: File[]) {
    const current = photosRef.current;
    const room = max - current.length;
    setTooMany(files.length > room);
    const added = files.slice(0, Math.max(0, room)).map((file) => ({
      id: nextId.current++, file, url: URL.createObjectURL(file),
    }));
    if (added.length > 0) setPhotos([...current, ...added]);
  }

  function remove(id: number) {
    const photo = photosRef.current.find((p) => p.id === id);
    if (!photo) return;
    URL.revokeObjectURL(photo.url);
    setPhotos(photosRef.current.filter((p) => p.id !== id));
    setTooMany(false);
  }

  function clear() {
    photosRef.current.forEach((p) => URL.revokeObjectURL(p.url));
    setPhotos([]);
  }

  /** Hides the "Choose up to N" line, for example when the picked photos are sent. */
  function dismissTooMany() {
    setTooMany(false);
  }

  return { photos, tooMany, add, remove, clear, dismissTooMany };
}

/** The picked photos as thumbnails, each with a remove button: "{Noun} N" and "Remove {noun} N". */
export function PhotoThumbs({
  photos, noun, busy, onRemove,
}: { photos: Photo[]; noun: string; busy: boolean; onRemove: (id: number) => void }) {
  if (photos.length === 0) return null;
  const Noun = noun.charAt(0).toUpperCase() + noun.slice(1);

  return (
    <ul className="mt-3 flex flex-wrap gap-3">
      {photos.map((p, i) => (
        <li key={p.id} className="relative">
          <img
            src={p.url}
            alt={`${Noun} ${i + 1}`}
            className="size-20 rounded-md border border-rule object-cover sm:size-24"
          />
          <button
            type="button"
            aria-label={`Remove ${noun} ${i + 1}`}
            disabled={busy}
            onClick={() => onRemove(p.id)}
            className="group absolute -top-3 -right-3 flex size-10 items-center justify-center rounded-full disabled:pointer-events-none disabled:opacity-45"
          >
            <span className="flex size-6 items-center justify-center rounded-full bg-ink text-on-ink ring-2 ring-surface transition-colors group-hover:bg-ink/90">
              <X aria-hidden="true" className="size-3.5" strokeWidth={2.5} />
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}
