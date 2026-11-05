import { render, screen } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import { MemoryRouter } from 'react-router';
import { recipeFixture } from '@/test/fixtures';
import { RecipeCard } from './RecipeCard';

// The bookmark and heart need auth and query providers that have nothing to do with the photo.
vi.mock('@/features/saved/BookmarkButton', () => ({ BookmarkButton: () => null }));
vi.mock('@/features/likes/LikeButton', () => ({ LikeButton: () => null }));

const renderCard = (overrides = {}) =>
  render(
    <MemoryRouter>
      <RecipeCard recipe={recipeFixture(overrides)} />
    </MemoryRouter>,
  );

describe('RecipeCard photo', () => {
  test('a Spoonacular photo gets a srcset and a sizes hint', () => {
    const { container } = renderCard({ image: 'https://img.spoonacular.com/recipes/7-312x231.jpg' });
    const img = container.querySelector('img')!;
    expect(img.getAttribute('srcset')).toContain('https://img.spoonacular.com/recipes/7-636x393.jpg 636w');
    expect(img.getAttribute('sizes')).toBe('(min-width: 1024px) 270px, 50vw');
  });

  test('an uploaded photo keeps a plain src', () => {
    const { container } = renderCard({ image: 'https://abc.supabase.co/storage/v1/object/public/p/a.jpg' });
    const img = container.querySelector('img')!;
    expect(img).not.toHaveAttribute('srcset');
    expect(img).toHaveAttribute('src', 'https://abc.supabase.co/storage/v1/object/public/p/a.jpg');
  });

  test('the whole card is one link to the recipe', () => {
    renderCard();
    expect(screen.getAllByRole('link')).toHaveLength(1);
  });
});
