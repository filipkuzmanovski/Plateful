export const MAX_SIDE = 1536;
export const MAX_BYTES = 1_000_000;
export const QUALITIES = [0.85, 0.7, 0.55];

/** The photo can't be decoded here (for example HEIC in Chrome on Windows) or won't fit in 1 MB. */
export class PhotoUnreadableError extends Error {
  constructor() {
    super('Photo unreadable');
    this.name = 'PhotoUnreadableError';
  }
}

const toJpeg = (canvas: HTMLCanvasElement, quality: number): Promise<Blob | null> =>
  new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality));

/**
 * Decodes with the EXIF orientation applied (so a portrait phone photo is upright and the backend never
 * sees a sideways image), scales the long side to at most 1,536 px (never upscaling), and encodes a JPEG
 * that carries no EXIF, trying lower qualities until it is at most 1,000,000 bytes.
 * Throws `PhotoUnreadableError` when it can't decode, or when even the lowest quality is too big.
 */
export async function shrinkPhoto(file: Blob): Promise<Blob> {
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    throw new PhotoUnreadableError();
  }

  let canvas: HTMLCanvasElement | null = null;
  try {
    const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height));
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new PhotoUnreadableError();
    ctx.drawImage(bitmap, 0, 0, width, height);

    for (const quality of QUALITIES) {
      const blob = await toJpeg(canvas, quality);
      if (!blob) throw new PhotoUnreadableError();
      if (blob.size <= MAX_BYTES) return blob;
    }
    throw new PhotoUnreadableError();
  } catch (e) {
    throw e instanceof PhotoUnreadableError ? e : new PhotoUnreadableError();
  } finally {
    bitmap.close();
    if (canvas) {
      canvas.width = 0; // frees the pixel buffer (iOS Safari caps total canvas memory)
      canvas.height = 0;
    }
  }
}
