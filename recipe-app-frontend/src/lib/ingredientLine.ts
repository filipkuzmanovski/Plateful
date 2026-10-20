const WORD_CHAR = /[\p{L}\p{N}]/u;

/** True when `needle` occurs in `haystack` starting at a word start (end unconstrained, so plurals match). */
function mentionsAtWordStart(haystack: string, needle: string): boolean {
  const text = haystack.toLowerCase();
  const target = needle.toLowerCase();
  for (let at = text.indexOf(target); at !== -1; at = text.indexOf(target, at + 1)) {
    if (at === 0 || !WORD_CHAR.test(text[at - 1])) return true;
  }
  return false;
}

/**
 * How to show an ingredient. `ingredientName` is a search hook ("flour") and `originalText` is the line
 * as written. Imported lines usually contain the name ("2 cups flour, sifted"); form-authored ones often
 * don't ("2 cups, sifted"). `name` is the text to bold in front of the line, or null when the line already
 * mentions it at a word start ("egg" matches "2 eggs", "oil" does not match "boiled"); an empty name counts
 * as mentioned.
 */
export function ingredientLine(
  ingredientName: string,
  originalText: string,
): { name: string | null; text: string } {
  const name = ingredientName.trim();
  const mentioned = name === '' || mentionsAtWordStart(originalText, name);
  return { name: mentioned ? null : name, text: originalText };
}
