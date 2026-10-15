/** Fails on emoji. The text symbols for copyright, registered and trademark are allowed: they are typographic, and the footer needs the copyright sign. */
export function expectNoEmoji(root: HTMLElement = document.body) {
  expect(root.textContent ?? '').not.toMatch(/(?![©®™])\p{Extended_Pictographic}/u);
}
