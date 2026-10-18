import { useEffect, useRef, type ReactNode } from 'react';
import { Play } from 'lucide-react';
import { formatClock } from '@/lib/formatClock';
import type { RecipeVideo } from '@/lib/types';

/** Share of the tile that must be on screen before the clip plays. */
const PLAY_AT_VISIBLE = 0.5;

const secondsLabel = (n: number) => `${n} ${n === 1 ? 'second' : 'seconds'}`;

/**
 * The recipe's video as a collage tile: the poster, a play icon and the length. While at least half of it is on
 * screen it plays muted and looping; it pauses when it leaves, never autoplays under `prefers-reduced-motion`,
 * and stays on the poster if the browser refuses to play. Clicking it opens the viewer, where the sound is on.
 */
export function VideoTile({
  video,
  compact = false,
  suspended = false,
  className = '',
  onOpen,
  children,
}: {
  video: RecipeVideo;
  /** A smaller play icon and duration chip, for the phone's row of square thumbnails. */
  compact?: boolean;
  /** True while the viewer is open: the tile pauses, and resumes on close only if it is still on screen. */
  suspended?: boolean;
  className?: string;
  onOpen: (opener: HTMLElement) => void;
  children?: ReactNode;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  // The last known state, so playback can be re-decided when `suspended` changes without a new observer reading.
  const visible = useRef(false);
  const suspendedRef = useRef(suspended);

  function sync() {
    const el = videoRef.current;
    if (!el) return;
    // Reduced motion is read at play time so a change of the setting mid-visit is honoured.
    const mayPlay = visible.current && !suspendedRef.current && !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if (mayPlay) {
      // An autoplay policy may refuse; the tile then simply stays on the poster.
      el.play()?.catch(() => {});
    } else {
      el.pause();
    }
  }

  useEffect(() => {
    const el = videoRef.current;
    // Without IntersectionObserver (an old browser, or SSR) the tile just shows its poster.
    if (!el || typeof IntersectionObserver === 'undefined') return;

    const observer = new IntersectionObserver(
      (entries) => {
        const latest = entries[entries.length - 1]; // readings can be batched; the newest is the truth
        visible.current = latest.isIntersecting && latest.intersectionRatio >= PLAY_AT_VISIBLE;
        sync();
      },
      { threshold: PLAY_AT_VISIBLE },
    );
    observer.observe(el);
    return () => observer.disconnect(); // a removed <video> stops by itself
  }, []);

  useEffect(() => {
    if (suspendedRef.current === suspended) return;
    suspendedRef.current = suspended;
    sync();
  }, [suspended]);

  return (
    <button
      type="button"
      aria-label={`Play video, ${secondsLabel(video.durationSeconds)}`}
      onClick={(e) => onOpen(e.currentTarget)}
      className={`group relative block min-h-0 min-w-0 cursor-pointer overflow-hidden bg-sand focus-visible:outline-offset-[-6px] ${className}`}
    >
      <video
        ref={videoRef}
        src={video.url}
        poster={video.posterUrl}
        muted
        loop
        playsInline
        preload="none"
        aria-hidden="true"
        tabIndex={-1}
        className="pointer-events-none absolute inset-0 size-full object-cover transition-transform duration-500 ease-out group-hover:scale-[1.03]"
      />
      <span className="pointer-events-none absolute inset-0 flex items-center justify-center">
        <span
          className={`flex items-center justify-center rounded-full bg-paper/90 text-olive shadow-lift transition group-hover:bg-paper ${
            compact ? 'size-8' : 'size-12'
          }`}
        >
          <Play
            aria-hidden="true"
            className={`translate-x-px fill-current ${compact ? 'size-3.5' : 'size-5'}`}
            strokeWidth={1.75}
          />
        </span>
      </span>
      <span
        className={`pointer-events-none absolute rounded-full bg-olive/85 font-semibold text-cream tabular-nums ${
          compact ? 'right-1 bottom-1 px-1.5 text-[10px] leading-4' : 'right-2 bottom-2 px-2 py-0.5 text-[11px]'
        }`}
      >
        {formatClock(video.durationSeconds)}
      </span>
      {/* Keyboard focus: a cream band just outside the olive outline, so the focus ring reads on dark and light posters. */}
      <span
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 ring-inset group-focus-visible:ring-4 group-focus-visible:ring-cream"
      />
      {children}
    </button>
  );
}
