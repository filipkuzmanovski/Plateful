import { ingredientLine } from './ingredientLine';

describe('ingredientLine', () => {
  test('prefixes the name when the text does not mention it', () => {
    expect(ingredientLine('flour', '2 cups, sifted')).toEqual({ name: 'flour', text: '2 cups, sifted' });
  });

  test('shows the line alone when it already mentions the name, ignoring case', () => {
    expect(ingredientLine('Flour', '2 cups flour, sifted')).toEqual({ name: null, text: '2 cups flour, sifted' });
    expect(ingredientLine('flour', '2 CUPS FLOUR')).toEqual({ name: null, text: '2 CUPS FLOUR' });
  });

  test('treats an empty or whitespace name as contained', () => {
    expect(ingredientLine('', '2 cups')).toEqual({ name: null, text: '2 cups' });
    expect(ingredientLine('   ', '2 cups')).toEqual({ name: null, text: '2 cups' });
  });

  test('trims whitespace around the name before comparing and returning it', () => {
    expect(ingredientLine('  flour  ', '2 cups flour')).toEqual({ name: null, text: '2 cups flour' });
    expect(ingredientLine('  flour  ', '2 cups, sifted')).toEqual({ name: 'flour', text: '2 cups, sifted' });
  });
});
