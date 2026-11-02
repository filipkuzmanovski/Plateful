import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './server';

// jsdom lacks the pointer-capture and scrolling APIs Radix Select relies on.
const proto = Element.prototype as unknown as Record<string, unknown>;
proto.hasPointerCapture ??= () => false;
proto.setPointerCapture ??= () => {};
proto.releasePointerCapture ??= () => {};
proto.scrollIntoView ??= () => {};
// jsdom logs "not implemented" for window.scrollTo; the content pages call it on open.
window.scrollTo = () => {};
// jsdom has no matchMedia; the theme provider reads it. Tests that need it stub it themselves.
window.matchMedia ??= ((query: string) => ({
  matches: false,
  media: query,
  onchange: null,
  addEventListener() {},
  removeEventListener() {},
  addListener() {},
  removeListener() {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia;

beforeAll(() => server.listen({ onUnhandledRequest: 'warn' }));
afterEach(() => {
  server.resetHandlers();
  // The theme choice and the applied theme must not leak between tests.
  localStorage.clear();
  delete document.documentElement.dataset.theme;
  document.documentElement.style.removeProperty('color-scheme');
});
afterAll(() => server.close());
