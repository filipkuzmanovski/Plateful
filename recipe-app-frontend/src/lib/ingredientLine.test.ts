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

  test('matches plurals, since only the start of the name is anchored', () => {
    expect(ingredientLine('egg', '2 eggs')).toEqual({ name: null, text: '2 eggs' });
  });

  test('matches a multi-word name', () => {
    expect(ingredientLine('olive oil', '2 tbsp olive oil')).toEqual({ name: null, text: '2 tbsp olive oil' });
  });

  test('does not match inside another word', () => {
    expect(ingredientLine('oil', '1 cup boiled water')).toEqual({ name: 'oil', text: '1 cup boiled water' });
    expect(ingredientLine('salt', '1 tsp unsalted butter')).toEqual({ name: 'salt', text: '1 tsp unsalted butter' });
  });
});
