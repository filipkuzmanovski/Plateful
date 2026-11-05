import { QueryClient } from '@tanstack/react-query';
import { pageOf, recipeFixture } from '@/test/fixtures';
import type { SavedItem } from '@/lib/types';
import { patchLikeCount, withLikeDelta } from './likeCounts';

const liked = recipeFixture({ id: 'r-1', likeCount: 2 });
const other = recipeFixture({ id: 'r-2', likeCount: 7 });
const stub: SavedItem = { kind: 'stub', recipe: null, stub: { id: 'r-1', approvedTitle: 'Old', state: 'unavailable' } };

describe('withLikeDelta', () => {
  test('patchesASingleRecipe', () => {
    expect(withLikeDelta(liked, 'r-1', 1).likeCount).toBe(3);
  });

  test('leavesAnotherSingleRecipeAlone', () => {
    expect(withLikeDelta(other, 'r-1', 1)).toBe(other);
  });

  test('patchesAPageOfRecipes', () => {
    const page = pageOf([liked, other]);
    const result = withLikeDelta(page, 'r-1', -1);
    expect(result.content[0].likeCount).toBe(1);
    expect(result.content[1]).toBe(other);
    expect(result.page).toBe(page.page);
  });

  test('patchesOnlyRecipeItemsOfAPageOfSavedItems', () => {
    const page = pageOf<SavedItem>([{ kind: 'recipe', recipe: liked, stub: null }, stub]);
    const result = withLikeDelta(page, 'r-1', 1);
    expect(result.content[0].recipe?.likeCount).toBe(3);
    expect(result.content[1]).toBe(stub);
  });

  test('patchesTheChaptersOfACookbook', () => {
    const book = { id: 'b-1', chapters: [{ kind: 'recipe', recipe: liked, stub: null } as SavedItem, stub] };
    const result = withLikeDelta(book, 'r-1', 1);
    expect(result.chapters[0].recipe?.likeCount).toBe(3);
    expect(result.chapters[1]).toBe(stub);
    expect(result.id).toBe('b-1');
  });

  test('returnsUnknownShapesUnchanged', () => {
    const unknown = { pages: [], pageParams: [] };
    expect(withLikeDelta(unknown, 'r-1', 1)).toBe(unknown);
    expect(withLikeDelta(undefined, 'r-1', 1)).toBeUndefined();
    expect(withLikeDelta(null, 'r-1', 1)).toBeNull();
    expect(withLikeDelta(['r-1'], 'r-1', 1)).toEqual(['r-1']);
  });

  test('neverGoesBelowZero', () => {
    expect(withLikeDelta(recipeFixture({ id: 'r-1', likeCount: 0 }), 'r-1', -1).likeCount).toBe(0);
  });

  test('aPageThatDoesNotHoldTheRecipeKeepsItsIdentity', () => {
    const page = pageOf([other]);
    expect(withLikeDelta(page, 'r-1', 1)).toBe(page);
  });
});

describe('patchLikeCount', () => {
  test('patchesEveryCachedPlaceARecipeAppears', () => {
    const client = new QueryClient();
    client.setQueryData(['recipes', { q: '' }], pageOf([liked]));
    client.setQueryData(['userRecipes', 'u-1', 0], pageOf([liked]));
    client.setQueryData(['savedRecipes', 'u-1', 0], pageOf<SavedItem>([{ kind: 'recipe', recipe: liked, stub: null }]));
    client.setQueryData(['likedRecipes', 'u-1', 0], pageOf<SavedItem>([{ kind: 'recipe', recipe: liked, stub: null }]));
    client.setQueryData(['recipe', 'r-1'], liked);
    client.setQueryData(['recipe', 'r-2'], other);
    client.setQueryData(['cookbook', 'b-1', 'u-1'], { chapters: [{ kind: 'recipe', recipe: liked, stub: null }] });

    patchLikeCount(client, 'r-1', 1);

    expect(client.getQueryData<{ content: { likeCount: number }[] }>(['recipes', { q: '' }])?.content[0].likeCount).toBe(3);
    expect(client.getQueryData<{ content: { likeCount: number }[] }>(['userRecipes', 'u-1', 0])?.content[0].likeCount).toBe(3);
    expect(client.getQueryData<{ content: SavedItem[] }>(['savedRecipes', 'u-1', 0])?.content[0].recipe?.likeCount).toBe(3);
    expect(client.getQueryData<{ content: SavedItem[] }>(['likedRecipes', 'u-1', 0])?.content[0].recipe?.likeCount).toBe(3);
    expect(client.getQueryData<{ likeCount: number }>(['recipe', 'r-1'])?.likeCount).toBe(3);
    expect(client.getQueryData<{ likeCount: number }>(['recipe', 'r-2'])?.likeCount).toBe(7);
    expect(client.getQueryData<{ chapters: SavedItem[] }>(['cookbook', 'b-1', 'u-1'])?.chapters[0].recipe?.likeCount).toBe(3);
  });
});
