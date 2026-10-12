import { afterEach, describe, expect, it, vi } from 'vitest';
import { prepareImage, targetSize } from './resizeImage';

const file = new File(['x'], 'p.png', { type: 'image/png' });
const fakeSrc = { width: 4000, height: 3000 } as unknown as ImageBitmap;

describe('targetSize', () => {
  it('scales the longest side down to 2000', () => {
    expect(targetSize(4000, 3000)).toEqual({ width: 2000, height: 1500 });
    expect(targetSize(3000, 4000)).toEqual({ width: 1500, height: 2000 });
  });
  it('never upscales', () => {
    expect(targetSize(800, 600)).toEqual({ width: 800, height: 600 });
  });
  it('rounds the results', () => {
    expect(targetSize(3001, 1999)).toEqual({ width: 2000, height: 1332 });
  });
});

describe('prepareImage', () => {
  it('encodes WebP at 0.85 on the shrunken size', async () => {
    const encode = vi.fn(async () => new Blob(['w'], { type: 'image/webp' }));
    const out = await prepareImage(file, { decode: async () => fakeSrc, encode });
    expect(out.ext).toBe('webp');
    expect(encode).toHaveBeenCalledWith(fakeSrc, 2000, 1500, 'image/webp', 0.85);
  });

  it('falls back to JPEG when WebP encoding returns null', async () => {
    const encode = vi.fn(async (_s: unknown, _w: number, _h: number, type: string) =>
      type === 'image/webp' ? null : new Blob(['j'], { type: 'image/jpeg' }));
    const out = await prepareImage(file, { decode: async () => fakeSrc, encode });
    expect(out.ext).toBe('jpg');
    expect(encode).toHaveBeenLastCalledWith(fakeSrc, 2000, 1500, 'image/jpeg', 0.85);
  });

  it('falls back to JPEG when the browser silently returns a non-WebP blob', async () => {
    const encode = vi.fn(async (_s: unknown, _w: number, _h: number, type: string) =>
      new Blob(['x'], { type: type === 'image/webp' ? 'image/png' : 'image/jpeg' }));
    const out = await prepareImage(file, { decode: async () => fakeSrc, encode });
    expect(out.ext).toBe('jpg');
    expect(out.blob.type).toBe('image/jpeg');
  });

  it("throws \"Couldn't read this image\" when decoding fails", async () => {
    const decode = async () => { throw new Error('bad'); };
    await expect(prepareImage(file, { decode, encode: async () => null }))
      .rejects.toThrow("Couldn't read this image");
  });

  it('throws when the result is over 5 MB', async () => {
    const big = new Blob([new Uint8Array(5_242_881)], { type: 'image/webp' });
    await expect(prepareImage(file, { decode: async () => fakeSrc, encode: async () => big }))
      .rejects.toThrow('Photo is too large even after resizing');
  });
});

describe('prepareImage releases the decoded bitmap', () => {
  it('closes the source once after a successful encode', async () => {
    const close = vi.fn();
    const src = { width: 4000, height: 3000, close } as unknown as ImageBitmap;
    await prepareImage(file, {
      decode: async () => src,
      encode: async () => new Blob(['w'], { type: 'image/webp' }),
    });
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('closes the source once when the encoder returns nothing', async () => {
    const close = vi.fn();
    const src = { width: 4000, height: 3000, close } as unknown as ImageBitmap;
    await expect(prepareImage(file, { decode: async () => src, encode: async () => null }))
      .rejects.toThrow("Couldn't read this image");
    expect(close).toHaveBeenCalledTimes(1);
  });

  it("surfaces an encoder that throws as \"Couldn't read this image\" and still closes", async () => {
    const close = vi.fn();
    const src = { width: 4000, height: 3000, close } as unknown as ImageBitmap;
    const encode = async () => { throw new DOMException('tainted', 'SecurityError'); };
    await expect(prepareImage(file, { decode: async () => src, encode }))
      .rejects.toThrow("Couldn't read this image");
    expect(close).toHaveBeenCalledTimes(1);
  });
});

describe('default decode and encode', () => {
  const bitmap = { width: 4000, height: 3000, close: vi.fn() };
  const drawImage = vi.fn();
  const fillRect = vi.fn();
  const ctx = { drawImage, fillRect, fillStyle: '', globalCompositeOperation: 'source-over' };
  let canvases: HTMLCanvasElement[] = [];

  function stubCanvas(blobFor: (type: string) => Blob | null) {
    canvases = [];
    const create = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = create(tag);
      if (tag === 'canvas') {
        canvases.push(el as HTMLCanvasElement);
        (el as HTMLCanvasElement).getContext = (() => ctx) as unknown as HTMLCanvasElement['getContext'];
        (el as HTMLCanvasElement).toBlob = ((cb: BlobCallback, type?: string) =>
          cb(blobFor(type ?? ''))) as HTMLCanvasElement['toBlob'];
      }
      return el;
    });
  }

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    bitmap.close.mockClear(); drawImage.mockClear(); fillRect.mockClear();
  });

  it('asks the browser to apply the EXIF orientation', async () => {
    const decode = vi.fn(async () => bitmap);
    vi.stubGlobal('createImageBitmap', decode);
    stubCanvas(() => new Blob(['w'], { type: 'image/webp' }));
    await prepareImage(file);
    expect(decode).toHaveBeenCalledWith(file, { imageOrientation: 'from-image' });
  });

  it('retries without options when the browser rejects them', async () => {
    const decode = vi.fn(async (_f: File, opts?: unknown) => {
      if (opts) throw new TypeError('bad options');
      return bitmap;
    });
    vi.stubGlobal('createImageBitmap', decode);
    stubCanvas(() => new Blob(['w'], { type: 'image/webp' }));
    await prepareImage(file);
    expect(decode).toHaveBeenCalledTimes(2);
    expect(decode).toHaveBeenLastCalledWith(file);
  });

  it('draws once on one canvas for the WebP attempt and the JPEG fallback, then releases it', async () => {
    vi.stubGlobal('createImageBitmap', async () => bitmap);
    stubCanvas((type) => new Blob(['x'], { type: type === 'image/webp' ? 'image/png' : 'image/jpeg' }));
    const out = await prepareImage(file);
    expect(out.ext).toBe('jpg');
    expect(canvases).toHaveLength(1);
    expect(drawImage).toHaveBeenCalledTimes(1);
    expect(fillRect).toHaveBeenCalledTimes(1); // white behind transparent pixels, JPEG only
    expect(canvases[0].width).toBe(0);
    expect(canvases[0].height).toBe(0);
    expect(bitmap.close).toHaveBeenCalledTimes(1);
  });

  it('does not paint white when WebP succeeds', async () => {
    vi.stubGlobal('createImageBitmap', async () => bitmap);
    stubCanvas(() => new Blob(['w'], { type: 'image/webp' }));
    await prepareImage(file);
    expect(fillRect).not.toHaveBeenCalled();
  });

  it("surfaces a drawImage failure as \"Couldn't read this image\" and still releases", async () => {
    vi.stubGlobal('createImageBitmap', async () => bitmap);
    stubCanvas(() => null);
    drawImage.mockImplementationOnce(() => { throw new Error('boom'); });
    await expect(prepareImage(file)).rejects.toThrow("Couldn't read this image");
    expect(canvases[0].width).toBe(0);
    expect(bitmap.close).toHaveBeenCalledTimes(1);
  });
});
