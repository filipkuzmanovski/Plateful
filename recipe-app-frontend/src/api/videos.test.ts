import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const upload = vi.fn();
const remove = vi.fn();
const from = vi.fn((_bucket: string) => ({ upload, remove }));

vi.mock('@/auth/supabaseClient', () => ({
  supabase: { storage: { from: (b: string) => from(b) } },
}));

import {
  ACCEPTED_VIDEO_TYPES, MAX_VIDEO_BYTES, MAX_VIDEO_SECONDS, VIDEO_BUCKET,
  deleteVideoFiles, pathFromPublicVideoUrl, publicVideoUrl, uploadPoster, uploadVideo,
} from './videos';

beforeEach(() => { upload.mockReset(); remove.mockReset(); from.mockClear(); });
afterEach(() => { vi.restoreAllMocks(); });

describe('constants', () => {
  it('match the bucket rules', () => {
    expect(VIDEO_BUCKET).toBe('recipe-videos');
    expect(MAX_VIDEO_BYTES).toBe(52_428_800);
    expect(MAX_VIDEO_SECONDS).toBe(60);
    expect(ACCEPTED_VIDEO_TYPES).toEqual(['video/mp4', 'video/webm', 'video/quicktime']);
  });
});

describe('public URLs', () => {
  it('builds the public storage URL and round-trips the path', () => {
    const url = publicVideoUrl('u/x.mp4');
    expect(url).toMatch(/\/storage\/v1\/object\/public\/recipe-videos\/u\/x\.mp4$/);
    expect(pathFromPublicVideoUrl(url)).toBe('u/x.mp4');
  });
  it('parses the path from a foreign host', () => {
    expect(pathFromPublicVideoUrl('https://other.host/storage/v1/object/public/recipe-videos/u/a.jpg')).toBe('u/a.jpg');
  });
  it('returns null for URLs outside the bucket', () => {
    expect(pathFromPublicVideoUrl('https://img.spoonacular.com/a.jpg')).toBeNull();
    expect(pathFromPublicVideoUrl('https://h/storage/v1/object/public/recipe-photos/u/a.jpg')).toBeNull();
  });
});

describe('uploadVideo', () => {
  it.each([
    ['video/mp4', 'mp4'],
    ['video/webm', 'webm'],
    ['video/quicktime', 'mov'],
  ])('puts a %s clip in the user folder as .%s', async (type, ext) => {
    upload.mockResolvedValue({ data: {}, error: null });
    const file = new File(['x'], 'clip.bin', { type });
    const path = await uploadVideo('u', file);
    expect(path).toMatch(new RegExp(`^u/[0-9a-f-]{36}\.${ext}$`));
    expect(from).toHaveBeenCalledWith('recipe-videos');
    expect(upload).toHaveBeenCalledWith(path, file, { contentType: type, upsert: false });
  });
  it('throws an Error with the storage message on failure', async () => {
    upload.mockResolvedValue({ data: null, error: { message: 'Payload too large' } });
    await expect(uploadVideo('u', new File(['x'], 'c.mp4', { type: 'video/mp4' }))).rejects.toThrow('Payload too large');
  });
  it('rejects a type it cannot name an extension for, without uploading', async () => {
    await expect(uploadVideo('u', new File(['x'], 'c.avi', { type: 'video/x-msvideo' }))).rejects.toThrow();
    expect(upload).not.toHaveBeenCalled();
  });
});

describe('uploadPoster', () => {
  it('uploads a JPEG to {userId}/{uuid}.jpg', async () => {
    upload.mockResolvedValue({ data: {}, error: null });
    const blob = new Blob(['x'], { type: 'image/jpeg' });
    const path = await uploadPoster('u', blob);
    expect(path).toMatch(/^u\/[0-9a-f-]{36}\.jpg$/);
    expect(from).toHaveBeenCalledWith('recipe-videos');
    expect(upload).toHaveBeenCalledWith(path, blob, { contentType: 'image/jpeg', upsert: false });
  });
  it('throws an Error with the storage message on failure', async () => {
    upload.mockResolvedValue({ data: null, error: { message: 'denied' } });
    await expect(uploadPoster('u', new Blob(['x'], { type: 'image/jpeg' }))).rejects.toThrow('denied');
  });
});

describe('deleteVideoFiles', () => {
  it('does nothing for an empty list', async () => {
    await deleteVideoFiles([]);
    expect(remove).not.toHaveBeenCalled();
  });
  it('removes the given paths from the video bucket', async () => {
    remove.mockResolvedValue({ data: [{ name: 'u/a.mp4' }, { name: 'u/a.jpg' }], error: null });
    await deleteVideoFiles(['u/a.mp4', 'u/a.jpg']);
    expect(from).toHaveBeenCalledWith('recipe-videos');
    expect(remove).toHaveBeenCalledWith(['u/a.mp4', 'u/a.jpg']);
  });
  it('warns when storage removed fewer files than requested', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    remove.mockResolvedValue({ data: [], error: null });
    await expect(deleteVideoFiles(['u/a.mp4'])).resolves.toBeUndefined();
    expect(warn).toHaveBeenCalledTimes(1);
  });
  it('warns and never throws on failure', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    remove.mockResolvedValue({ data: null, error: { message: 'nope' } });
    await expect(deleteVideoFiles(['u/a.mp4'])).resolves.toBeUndefined();
    remove.mockRejectedValue(new Error('network'));
    await expect(deleteVideoFiles(['u/a.mp4'])).resolves.toBeUndefined();
    expect(warn).toHaveBeenCalledTimes(2);
  });
});
