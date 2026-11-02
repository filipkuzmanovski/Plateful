import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode, type RefObject } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { ChevronLeft, ChevronRight, UtensilsCrossed, X } from 'lucide-react';
import { DETAIL_IMAGE_SIZES, recipeImageSrcSet } from '@/lib/recipeImage';
import type { RecipeVideo } from '@/lib/types';
import { VideoTile } from './VideoTile';

/** The most tiles the wide layout ever shows; photos past that collapse into a "+N" on the last tile. */
const WIDE_TILES = 3;

/** One entry in the collage and the viewer: a photo, or the recipe's video. */
export type CollageMedia = { kind: 'photo'; src: string } | ({ kind: 'video' } & RecipeVideo);

/** Cover photo, then the video, then the remaining photos. With no photos the video is the cover. */
function buildMedia(photos: string[], video: RecipeVideo | null | undefined): CollageMedia[] {
  const items: CollageMedia[] = photos.map((src) => ({ kind: 'photo', src }));
  if (video) items.splice(Math.min(1, items.length), 0, { kind: 'video', ...video });
  return items;
}

/** Where photo `index` sits among the photos only ("photo 2 of 3" ignores the video). */
const photoNumber = (media: CollageMedia[], index: number) =>
  media.slice(0, index + 1).filter((m) => m.kind === 'photo').length;

type TileProps = {
  src: string;
  alt: string;
  label: string;
  /** The cover is above the fold, so it loads eagerly; everything else waits until it is near the viewport. */
  eager?: boolean;
  /** The cover is shown large, so it offers the browser the photo's other sizes. */
  responsive?: boolean;
  className?: string;
  onOpen: (opener: HTMLElement) => void;
  children?: ReactNode;
};

/** One photo as a button. Hover only darkens it slightly; there is no zoom. */
function Tile({ src, alt, label, eager = false, responsive = false, className = '', onOpen, children }: TileProps) {
  const srcSet = responsive ? recipeImageSrcSet(src) : undefined;
  return (
    <button
      type="button"
      aria-label={label}
      onClick={(e) => onOpen(e.currentTarget)}
      className={`group relative block min-h-0 min-w-0 cursor-pointer overflow-hidden bg-tint focus-visible:outline-offset-[-6px] ${className}`}
    >
      <img
        src={src}
        srcSet={srcSet}
        sizes={srcSet ? DETAIL_IMAGE_SIZES : undefined}
        alt={alt}
        loading={eager ? undefined : 'lazy'}
        decoding="async"
        draggable={false}
        className="absolute inset-0 size-full object-cover transition-[filter] group-hover:brightness-90"
      />
      {/* Hover: a slight darkening of the photo (brightness, so it works in both themes). */}
      {/* Keyboard focus: a page-coloured band just outside the ink outline, so the focus ring reads on dark and light photos. */}
      <span
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 ring-inset group-focus-visible:ring-4 group-focus-visible:ring-page"
      />
      {children}
    </button>
  );
}

/**
 * The recipe's photos and video as a collage: cover large, up to two more beside it, "+N" for the rest.
 * Below 640px it is a full-width cover with a row of square thumbnails. Every tile opens `PhotoViewer`.
 */
export function PhotoCollage({
  photos,
  video = null,
  title,
}: {
  photos: string[];
  video?: RecipeVideo | null;
  title: string;
}) {
  const [open, setOpen] = useState(false);
  const [index, setIndex] = useState(0);
  // Radix only returns focus to a Dialog.Trigger, and the tiles aren't one, so remember the tile that opened the viewer.
  const opener = useRef<HTMLElement | null>(null);

  const media = buildMedia(photos, video);
  const total = media.length;

  if (total === 0) {
    return (
      <div className="flex aspect-[4/3] items-center justify-center bg-tint text-ink-soft">
        <UtensilsCrossed aria-hidden="true" className="size-14" strokeWidth={1.25} />
      </div>
    );
  }

  const show = (i: number) => (el: HTMLElement) => {
    opener.current = el;
    setIndex(i);
    setOpen(true);
  };
  const tile = (i: number, className: string, children?: ReactNode, compact = false) => {
    const item = media[i];
    if (item.kind === 'video') {
      return (
        <VideoTile key={i} video={item} compact={compact} suspended={open} className={className} onOpen={show(i)}>
          {children}
        </VideoTile>
      );
    }
    const n = photoNumber(media, i);
    return (
      <Tile
        key={i}
        src={item.src}
        alt={`${title}, photo ${n} of ${photos.length}`}
        label={`Open photo ${n} of ${photos.length}`}
        eager={i === 0}
        responsive={i === 0}
        onOpen={show(i)}
        className={className}
      >
        {children}
      </Tile>
    );
  };

  const wideCount = Math.min(total, WIDE_TILES);
  const hidden = total - WIDE_TILES;

  return (
    <>
      {/* Phone: the cover at full width, then the rest as a row of squares. */}
      <div data-testid="collage-phone" className="flex flex-col gap-1 sm:hidden">
        {tile(0, 'aspect-[4/3] w-full')}
        {total > 1 && (
          <div className="grid grid-cols-5 gap-1">
            {media.slice(1).map((_, i) => tile(i + 1, 'aspect-square', undefined, true))}
          </div>
        )}
      </div>

      {/* Wider screens: a fixed 4:3 frame, split by how many tiles there are. */}
      <div
        data-testid="collage-desktop"
        className={`hidden aspect-[4/3] gap-1 sm:grid ${
          wideCount === 1 ? 'grid-cols-1' : wideCount === 2 ? 'grid-cols-2' : 'grid-cols-3 grid-rows-2'
        }`}
      >
        {Array.from({ length: wideCount }, (_, i) =>
          tile(
            i,
            wideCount === 3 && i === 0 ? 'col-span-2 row-span-2' : '',
            i === WIDE_TILES - 1 && hidden > 0 && (
              // Mode-fixed dark scrim with white text (photo viewer allowance): `ink` is near-white in dark mode and would wash the photo out.
              <span className="absolute inset-0 flex items-center justify-center bg-black/55 font-serif text-4xl font-medium text-white transition-colors group-hover:bg-black/65">
                +{hidden}
              </span>
            ),
          ),
        )}
      </div>

      <PhotoViewer
        media={media}
        title={title}
        index={index}
        onIndexChange={setIndex}
        open={open}
        onOpenChange={setOpen}
        returnFocusTo={opener}
      />
    </>
  );
}

const iconButton =
  'flex size-11 items-center justify-center rounded-full bg-surface/90 text-ink shadow-float transition hover:bg-surface focus-visible:outline-white';

const VIDEO_ERROR = "This video couldn't be played.";

/**
 * The viewer's player: controls on, sound on (the visitor just asked for it) and no loop. Pausing is explicit
 * because a removed `<video>` isn't guaranteed to stop: it pauses when the viewer closes or moves to another item.
 */
function ViewerVideo({ video, title, open }: { video: RecipeVideo; title: string; open: boolean }) {
  const ref = useRef<HTMLVideoElement>(null);
  const [failed, setFailed] = useState(false);

  // Cleanup runs when `open` turns false (the dialog may linger for its exit animation) and on unmount.
  useEffect(() => {
    const el = ref.current;
    return () => el?.pause();
  }, [open]);

  if (failed) {
    return (
      <p role="alert" className="rounded-card bg-surface px-6 py-5 text-center font-medium text-ink shadow-float">
        {VIDEO_ERROR}
      </p>
    );
  }
  return (
    <video
      ref={ref}
      src={video.url}
      poster={video.posterUrl}
      aria-label={`${title}, video`}
      controls
      autoPlay
      playsInline
      onError={() => setFailed(true)}
      className="max-h-full max-w-full animate-overlay-in rounded-md bg-black object-contain shadow-float"
    />
  );
}

/**
 * Full-screen viewer for the photos and the video. Previous/Next wrap around at the ends, and
 * ArrowLeft/ArrowRight do the same; Escape and the backdrop close it (Radix handles Escape).
 */
export function PhotoViewer({
  media,
  title,
  index,
  onIndexChange,
  open,
  onOpenChange,
  returnFocusTo,
}: {
  media: CollageMedia[];
  title: string;
  index: number;
  onIndexChange: (index: number) => void;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** The element to focus on close. Falls back to whatever had focus when the viewer opened. */
  returnFocusTo?: RefObject<HTMLElement | null>;
}) {
  const focusedBefore = useRef<HTMLElement | null>(null);
  const total = media.length;
  const many = total > 1;
  const item = media[index];
  const hasVideo = media.some((m) => m.kind === 'video');
  const hasPhotos = media.some((m) => m.kind === 'photo');
  const subject = hasVideo ? (hasPhotos ? 'photos and video' : 'video') : 'photos';
  const go = (step: number) => onIndexChange((index + step + total) % total);

  function onKeyDown(e: KeyboardEvent) {
    if (!many) return;
    // The video's own controls use the arrows to seek; leave them alone.
    if (e.target instanceof HTMLMediaElement) return;
    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      go(-1);
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      go(1);
    }
  }

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 animate-overlay-in bg-black/85 data-[state=closed]:animate-overlay-out" />
        <Dialog.Content
          onKeyDown={onKeyDown}
          onOpenAutoFocus={() => {
            focusedBefore.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
          }}
          onCloseAutoFocus={(e) => {
            e.preventDefault();
            (returnFocusTo?.current ?? focusedBefore.current)?.focus();
          }}
          // A click on the empty area around the photo closes the viewer; clicks on the photo or controls don't.
          onClick={(e) => {
            if (e.target === e.currentTarget) onOpenChange(false);
          }}
          className="fixed inset-0 z-50 flex animate-overlay-in items-center justify-center p-4 outline-none data-[state=closed]:animate-overlay-out sm:px-20 sm:py-14"
        >
          <Dialog.Title className="sr-only">{title} {subject}</Dialog.Title>
          <Dialog.Description className="sr-only">
            {many
              ? `Use the arrow keys or the buttons to move between ${hasVideo ? 'items' : 'photos'}.`
              : item?.kind === 'video'
                ? 'The recipe video.'
                : 'Full-size photo.'}
          </Dialog.Description>

          {item?.kind === 'video' ? (
            <ViewerVideo key={index} video={item} title={title} open={open} />
          ) : (
            item && (
              <img
                key={index}
                src={item.src}
                alt={`${title}, photo ${photoNumber(media, index)} of ${media.filter((m) => m.kind === 'photo').length}`}
                className="max-h-full max-w-full animate-overlay-in rounded-md object-contain shadow-float"
              />
            )
          )}

          <Dialog.Close aria-label="Close" className={`absolute top-3 right-3 sm:top-5 sm:right-5 ${iconButton}`}>
            <X aria-hidden="true" className="size-5" strokeWidth={2} />
          </Dialog.Close>

          {many && (
            <>
              <span
                aria-live="polite"
                className="absolute top-4 left-1/2 -translate-x-1/2 rounded-full bg-surface/90 px-3.5 py-1.5 text-[15px] font-medium text-ink tabular-nums sm:top-6"
              >
                {index + 1} / {total}
              </span>
              <button
                type="button"
                aria-label={hasVideo ? 'Previous' : 'Previous photo'}
                onClick={() => go(-1)}
                className={`absolute top-1/2 left-3 -translate-y-1/2 sm:left-5 ${iconButton}`}
              >
                <ChevronLeft aria-hidden="true" className="size-6" strokeWidth={2} />
              </button>
              <button
                type="button"
                aria-label={hasVideo ? 'Next' : 'Next photo'}
                onClick={() => go(1)}
                className={`absolute top-1/2 right-3 -translate-y-1/2 sm:right-5 ${iconButton}`}
              >
                <ChevronRight aria-hidden="true" className="size-6" strokeWidth={2} />
              </button>
            </>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
