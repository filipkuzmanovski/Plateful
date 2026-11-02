import { describe, expect, it } from 'vitest';
import { CARD_IMAGE_SIZES, DETAIL_IMAGE_SIZES, recipeImageSrcSet } from './recipeImage';

describe('recipeImageSrcSet', () => {
  it('lists the three Spoonacular sizes for a sized jpg', () => {
    expect(recipeImageSrcSet('https://img.spoonacular.com/recipes/1095838-312x231.jpg')).toBe(
      'https://img.spoonacular.com/recipes/1095838-312x231.jpg 312w, https://img.spoonacular.com/recipes/1095838-480x360.jpg 480w, https://img.spoonacular.com/recipes/1095838-636x393.jpg 636w',
    );
  });

  it('keeps the file extension', () => {
    expect(recipeImageSrcSet('https://img.spoonacular.com/recipes/42-556x370.png')).toBe(
      'https://img.spoonacular.com/recipes/42-312x231.png 312w, https://img.spoonacular.com/recipes/42-480x360.png 480w, https://img.spoonacular.com/recipes/42-636x393.png 636w',
    );
  });

  it('keeps an http scheme', () => {
    expect(recipeImageSrcSet('http://img.spoonacular.com/recipes/9-90x90.jpg')).toBe(
      'http://img.spoonacular.com/recipes/9-312x231.jpg 312w, http://img.spoonacular.com/recipes/9-480x360.jpg 480w, http://img.spoonacular.com/recipes/9-636x393.jpg 636w',
    );
  });

  it('returns undefined for a Spoonacular URL without a size', () => {
    expect(recipeImageSrcSet('https://img.spoonacular.com/recipes/1095838.jpg')).toBeUndefined();
  });

  it('returns undefined for uploads, null and empty strings', () => {
    expect(
      recipeImageSrcSet('https://abc.supabase.co/storage/v1/object/public/recipe-photos/a/b-640x480.jpg'),
    ).toBeUndefined();
    expect(recipeImageSrcSet(null)).toBeUndefined();
    expect(recipeImageSrcSet(undefined)).toBeUndefined();
    expect(recipeImageSrcSet('')).toBeUndefined();
  });

  it('exports the sizes hints', () => {
    expect(CARD_IMAGE_SIZES).toBe('(min-width: 1024px) 270px, 50vw');
    expect(DETAIL_IMAGE_SIZES).toBe('(min-width: 1024px) 460px, 100vw');
  });
});
