// The only browser-media code in the app. jsdom cannot decode media, so tests replace this module.

const PROBE_TIMEOUT_MS = 15_000;
const MAX_POSTER_SIDE = 1280;
const POSTER_QUALITY = 0.85;

type ProbeResult = { playable: true; durationSeconds: number } | { playable: false };

function detachedVideo(src: string): HTMLVideoElement {
  const video = document.createElement('video');
  video.muted = true;
  video.playsInline = true;
  video.preload = 'metadata';
  video.src = src;
  return video;
}

const isUsable = (d: number): boolean => Number.isFinite(d) && d > 0;

/** Loads only the metadata. Unplayable on a decode error, a zero/infinite/NaN duration, or after 15 s. */
export function probeVideo(file: File): Promise<ProbeResult> {
  const url = URL.createObjectURL(file);
  const video = detachedVideo(url);
  return new Promise<ProbeResult>((resolve) => {
    const timer = setTimeout(() => finish({ playable: false }), PROBE_TIMEOUT_MS);
    function finish(result: ProbeResult) {
      clearTimeout(timer);
      video.removeAttribute('src');
      video.load();
      URL.revokeObjectURL(url);
      resolve(result);
    }
    video.onerror = () => finish({ playable: false });
    video.onloadedmetadata = () => finish(
      isUsable(video.duration) ? { playable: true, durationSeconds: video.duration } : { playable: false },
    );
  });
}

/** A JPEG frame (longest side at most 1280 px) taken at min(atSeconds, duration / 2). */
export function capturePoster(file: File, atSeconds: number): Promise<Blob> {
  const url = URL.createObjectURL(file);
  const video = detachedVideo(url);
  return new Promise<Blob>((resolve, reject) => {
    const timer = setTimeout(() => finish(new Error('Timed out capturing the poster')), PROBE_TIMEOUT_MS);
    function finish(failure: Error | null, blob?: Blob) {
      clearTimeout(timer);
      video.removeAttribute('src');
      video.load();
      URL.revokeObjectURL(url);
      if (failure || !blob) reject(failure ?? new Error('Could not capture the poster'));
      else resolve(blob);
    }
    video.onerror = () => finish(new Error('Could not decode the video'));
    video.onloadedmetadata = () => {
      if (!isUsable(video.duration)) return finish(new Error('The video has no usable duration'));
      video.currentTime = Math.min(atSeconds, video.duration / 2);
    };
    video.onseeked = () => {
      const scale = Math.min(1, MAX_POSTER_SIDE / Math.max(video.videoWidth, video.videoHeight));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(video.videoWidth * scale));
      canvas.height = Math.max(1, Math.round(video.videoHeight * scale));
      const ctx = canvas.getContext('2d');
      if (!ctx) return finish(new Error('Canvas is not available'));
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
      canvas.toBlob(
        (blob) => finish(blob ? null : new Error('Could not encode the poster'), blob ?? undefined),
        'image/jpeg',
        POSTER_QUALITY,
      );
    };
  });
}
