import { buildShareLinks } from './shareLinks';

const base = { origin: 'https://plateful.test', id: 'r-1', title: 'Mac & Cheese #1 Bake', image: 'https://img.test/a b.jpg?x=1&y=2' };

describe('buildShareLinks', () => {
  const links = buildShareLinks(base);
  const url = encodeURIComponent('https://plateful.test/recipes/r-1');
  const text = encodeURIComponent('Mac & Cheese #1 Bake on Plateful');

  test('shared data', () => {
    expect(links.url).toBe('https://plateful.test/recipes/r-1');
    expect(links.text).toBe('Mac & Cheese #1 Bake on Plateful');
    expect(links.title).toBe('Mac & Cheese #1 Bake');
  });

  test('exact, encoded URLs for each network', () => {
    expect(links.facebook).toBe(`https://www.facebook.com/sharer/sharer.php?u=${url}`);
    expect(links.x).toBe(`https://twitter.com/intent/tweet?url=${url}&text=${text}`);
    expect(links.whatsapp).toBe(`https://wa.me/?text=${text}%20${url}`);
    expect(links.pinterest).toBe(
      `https://pinterest.com/pin/create/button/?url=${url}&media=${encodeURIComponent(base.image)}&description=${text}`,
    );
    expect(links.email).toBe(`mailto:?subject=${encodeURIComponent('Mac & Cheese #1 Bake')}&body=${text}%0A${url}`);
  });

  test('encoding leaves no raw ampersand, hash or space in a value', () => {
    expect(links.x).toContain('Mac%20%26%20Cheese%20%231%20Bake%20on%20Plateful');
    expect(links.email).not.toMatch(/subject=[^&]*[ #]/);
  });

  test('Pinterest omits media when there is no image', () => {
    const none = buildShareLinks({ ...base, image: null });
    expect(none.pinterest).toBe(`https://pinterest.com/pin/create/button/?url=${url}&description=${text}`);
    expect(none.pinterest).not.toContain('media');
  });

  test('the id is encoded in the URL', () => {
    expect(buildShareLinks({ ...base, id: 'a/b?c' }).url).toBe('https://plateful.test/recipes/a%2Fb%3Fc');
  });

  test('a hostile title cannot add parameters or header lines', () => {
    const evil = buildShareLinks({ ...base, title: 'x&cc=evil@x.com\nBcc: y', image: null });
    for (const href of [evil.facebook, evil.x, evil.whatsapp, evil.pinterest, evil.email]) {
      expect(href).not.toContain('&cc=');
      expect(href).not.toMatch(/[\r\n]/);
    }
    expect(evil.email).toContain('subject=x%26cc%3Devil%40x.com%0ABcc%3A%20y&body=');
  });
});
