/**
 * How to show an ingredient. `ingredientName` is a search hook ("flour") and `originalText` is the line
 * as written. Imported lines usually contain the name ("2 cups flour, sifted"); form-authored ones often
 * don't ("2 cups, sifted"). `name` is the text to bold in front of the line, or null when the line already
 * mentions it (an empty name counts as mentioned).
 */
export function ingredientLine(
  ingredientName: string,
  originalText: string,
): { name: string | null; text: string } {
  const name = ingredientName.trim();
  const mentioned = name === '' || originalText.toLowerCase().includes(name.toLowerCase());
  return { name: mentioned ? null : name, text: originalText };
}
