export function expectNoEmoji(root: HTMLElement = document.body) {
  expect(root.textContent ?? '').not.toMatch(/\p{Extended_Pictographic}/u);
}
