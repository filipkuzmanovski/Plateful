import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MAX_BYTES, MAX_SIDE, PhotoUnreadableError, QUALITIES, shrinkPhoto } from './shrinkPhoto';

const file = new File(['x'], 'p.jpg', { type: 'image/jpeg' });

function bitmapOf(width: number, height: number) {
  return { width, height, close: vi.fn() };
}

/** Stubs the canvas so toBlob answers with blobs of the given sizes, one per call; records qualities. */
function stubCanvas(sizes: number[]) {
  const drawImage = vi.fn();
  const qualities: number[] = [];
  const types: (string | undefined)[] = [];
  const canvases: HTMLCanvasElement[] = [];
  let call = 0;
  const create = document.createElement.bind(document);
  vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
    const el = create(tag);
    if (tag === 'canvas') {
      canvases.push(el as HTMLCanvasElement);
      (el as HTMLCanvasElement).getContext = (() => ({ drawImage })) as unknown as HTMLCanvasElement['getContext'];
      (el as HTMLCanvasElement).toBlob = ((cb: BlobCallback, type?: string, quality?: number) => {
        types.push(type);
        qualities.push(quality as number);
        const size = sizes[Math.min(call, sizes.length - 1)];
        call += 1;
        cb(new Blob([new Uint8Array(size)], { type: 'image/jpeg' }));
      }) as HTMLCanvasElement['toBlob'];
    }
    return el;
  });
  return { drawImage, qualities, types, canvases };
}

describe('shrinkPhoto', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('exports the limits', () => {
    expect(MAX_SIDE).toBe(1536);
    expect(MAX_BYTES).toBe(1_000_000);
    expect(QUALITIES).toEqual([0.85, 0.7, 0.55]);
  });

  it('passesImageOrientationFromImage', async () => {
    const decode = vi.fn(async () => bitmapOf(800, 600));
    vi.stubGlobal('createImageBitmap', decode);
    stubCanvas([100]);
    await shrinkPhoto(file);
    expect(decode).toHaveBeenCalledWith(file, { imageOrientation: 'from-image' });
  });

  it('scalesLongSideTo1536', async () => {
    vi.stubGlobal('createImageBitmap', async () => bitmapOf(3000, 2000));
    const { canvases, drawImage } = stubCanvas([100]);
    await shrinkPhoto(file);
    expect(drawImage).toHaveBeenCalledTimes(1);
    expect(drawImage.mock.calls[0].slice(1)).toEqual([0, 0, 1536, 1024]);
    expect(canvases[0].width).toBe(0); // released after use
  });

  it('scales a portrait photo on its height', async () => {
    vi.stubGlobal('createImageBitmap', async () => bitmapOf(2000, 3000));
    const { drawImage } = stubCanvas([100]);
    await shrinkPhoto(file);
    expect(drawImage.mock.calls[0].slice(1)).toEqual([0, 0, 1024, 1536]);
  });

  it('doesNotUpscale', async () => {
    vi.stubGlobal('createImageBitmap', async () => bitmapOf(800, 600));
    const { drawImage } = stubCanvas([100]);
    await shrinkPhoto(file);
    expect(drawImage.mock.calls[0].slice(1)).toEqual([0, 0, 800, 600]);
  });

  it('encodes a JPEG at 0.85 first and closes the bitmap', async () => {
    const bitmap = bitmapOf(800, 600);
    vi.stubGlobal('createImageBitmap', async () => bitmap);
    const { qualities, types } = stubCanvas([500_000]);
    const out = await shrinkPhoto(file);
    expect(types).toEqual(['image/jpeg']);
    expect(qualities).toEqual([0.85]);
    expect(out.size).toBe(500_000);
    expect(bitmap.close).toHaveBeenCalledTimes(1);
  });

  it('triesLowerQualitiesWhenTooBig', async () => {
    vi.stubGlobal('createImageBitmap', async () => bitmapOf(3000, 2000));
    const { qualities, drawImage } = stubCanvas([1_200_000, 1_100_000, 900_000]);
    const out = await shrinkPhoto(file);
    expect(qualities).toEqual([0.85, 0.7, 0.55]);
    expect(out.size).toBe(900_000);
    expect(drawImage).toHaveBeenCalledTimes(1); // drawn once, re-encoded
  });

  it('stillTooBig_throwsPhotoUnreadableError', async () => {
    const bitmap = bitmapOf(3000, 2000);
    vi.stubGlobal('createImageBitmap', async () => bitmap);
    const { canvases } = stubCanvas([1_200_000, 1_100_000, 1_050_000]);
    await expect(shrinkPhoto(file)).rejects.toBeInstanceOf(PhotoUnreadableError);
    expect(bitmap.close).toHaveBeenCalledTimes(1);
    expect(canvases[0].width).toBe(0);
  });

  it('decodeFailure_throwsPhotoUnreadableError', async () => {
    vi.stubGlobal('createImageBitmap', async () => { throw new DOMException('no decoder', 'InvalidStateError'); });
    stubCanvas([100]);
    await expect(shrinkPhoto(file)).rejects.toBeInstanceOf(PhotoUnreadableError);
  });

  it('throws PhotoUnreadableError when the canvas gives no blob', async () => {
    const bitmap = bitmapOf(800, 600);
    vi.stubGlobal('createImageBitmap', async () => bitmap);
    const create = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = create(tag);
      if (tag === 'canvas') {
        (el as HTMLCanvasElement).getContext = (() => ({ drawImage: vi.fn() })) as unknown as HTMLCanvasElement['getContext'];
        (el as HTMLCanvasElement).toBlob = ((cb: BlobCallback) => cb(null)) as HTMLCanvasElement['toBlob'];
      }
      return el;
    });
    await expect(shrinkPhoto(file)).rejects.toBeInstanceOf(PhotoUnreadableError);
    expect(bitmap.close).toHaveBeenCalledTimes(1);
  });
});
