import { afterEach, expect, test, vi } from 'vitest';
import { scanPhotos } from '@/api/ai';

vi.mock('@/auth/supabaseClient', () => ({
  supabase: {
    auth: {
      getSession: async () => ({ data: { session: null } }),
      signOut: async () => {},
    },
  },
}));

afterEach(() => {
  vi.restoreAllMocks();
});

// The request is inspected at the fetch boundary: jsdom's FormData and Blob do not round-trip through
// a mocked server's body parser, so the test reads the FormData the client hands to fetch.
test('scanPhotos posts each blob as a photos part named photo-N.jpg and returns the result', async () => {
  const scan = {
    ingredients: ['egg'],
    leftOut: [{ ingredient: 'peanut butter', reason: 'allergy: Peanuts' }],
    remainingScans: 7,
  };
  const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify(scan), { status: 200, headers: { 'Content-Type': 'application/json' } }),
  );

  const result = await scanPhotos([
    new Blob(['a'], { type: 'image/jpeg' }),
    new Blob(['b'], { type: 'image/jpeg' }),
  ]);

  expect(fetchSpy).toHaveBeenCalledTimes(1);
  const [url, init] = fetchSpy.mock.calls[0];
  expect(String(url)).toBe('http://localhost:8080/api/users/me/ai-recipes/scan');
  expect(init?.method).toBe('POST');
  expect(new Headers(init?.headers).has('Content-Type')).toBe(false); // the browser adds the multipart boundary
  const form = init?.body as FormData;
  expect(form).toBeInstanceOf(FormData);
  expect([...form.keys()]).toEqual(['photos', 'photos']);
  expect(form.getAll('photos').map((p) => (p as File).name)).toEqual(['photo-1.jpg', 'photo-2.jpg']);
  expect(result).toEqual(scan);
});
