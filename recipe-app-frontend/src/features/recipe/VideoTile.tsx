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
  className = '',
  onOpen,
  children,
}: {
  video: RecipeVideo;
  className?: string;
  onOpen: (opener: HTMLElement) => void;
  children?: ReactNode;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    const el = videoRef.current;
    // Without IntersectionObserver (an old browser, or SSR) the tile just shows its poster.
    if (!el || typeof IntersectionObserver === 'undefined') return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting && entry.intersectionRatio >= PLAY_AT_VISIBLE) {
          // Read at play time so a change of the setting mid-visit is honoured.
          if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;
          // An autoplay policy may refuse; the tile then simply stays on the poster.
          el.play()?.catch(() => {});
        } else {
          el.pause();
        }
      },
      { threshold: PLAY_AT_VISIBLE },
    );
    observer.observe(el);
    return () => observer.disconnect(); // a removed <video> stops by itself
  }, []);

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
        <span className="flex size-12 items-center justify-center rounded-full bg-paper/90 text-olive shadow-lift transition group-hover:bg-paper">
          <Play aria-hidden="true" className="size-5 translate-x-px fill-current" strokeWidth={1.75} />
        </span>
      </span>
      <span className="pointer-events-none absolute right-2 bottom-2 rounded-full bg-olive/85 px-2 py-0.5 text-[11px] font-semibold text-cream tabular-nums">
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
