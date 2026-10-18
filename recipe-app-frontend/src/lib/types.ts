export interface UserDisplay { id: string; firstName: string; lastName: string; }
/** The signed-in user's own profile: the public fields plus what only they (and the review flow) need. */
export interface MyProfile extends UserDisplay { isAdmin: boolean; rejectedCount: number; }
export interface IngredientDisplay { ingredientName: string; originalText: string; }
export interface InstructionStep { number: number; step: string; ingredients: string[]; equipment: string[]; }

export type ReviewStatus = 'pending' | 'approved' | 'rejected';
export interface RecipeReview {
  status: ReviewStatus; rejectionReason: string | null; everApproved: boolean;
  revision: number; submittedAt: string;
}

export interface RecipeDisplay {
  id: string; source: string; user: UserDisplay | null; title: string;
  image: string | null; servings: number | null; readyInMinutes: number | null;
  cookingMinutes: number | null; preparationMinutes: number | null;
  sourceName: string | null; sourceUrl: string | null; instructions: string | null;
  instructionsSteps: InstructionStep[]; dairyFree: boolean; glutenFree: boolean;
  vegan: boolean; vegetarian: boolean; cuisines: string[];
  calories: number | null; proteinGrams: number | null; fatGrams: number | null;
  carbsGrams: number | null; nutrition: Record<string, unknown> | null;
  ingredients: IngredientDisplay[]; averageRating: number | null;
  ratingCount: number; commentCount: number; createdAt: string | null;
  photos: string[];
  /** Null for anyone but the author or an admin. */
  review?: RecipeReview | null;
}

export interface CommentDisplay { id: string; user: UserDisplay; body: string; likeCount: number; dislikeCount: number; createdAt: string | null; }
export interface RatingDisplay { id: string; user: UserDisplay; rating: number; createdAt: string | null; }
export interface ReactionDisplay { user: UserDisplay; isLike: boolean; createdAt: string | null; }

/** Why a saved recipe the reader can no longer open is hidden. */
export type HiddenState = 'under_review' | 'unavailable';

/** One entry of the saved list: the recipe itself, or a stub that keeps its place while the recipe is hidden. */
export type SavedItem =
  | { kind: 'recipe'; recipe: RecipeDisplay; stub: null }
  | { kind: 'stub'; recipe: null; stub: { id: string; approvedTitle: string; state: HiddenState } };

export interface Page<T> { content: T[]; page: { size: number; number: number; totalElements: number; totalPages: number }; }

export interface RecipeFilter {
  cuisine?: string[]; region?: string[]; sea?: string[]; dairyFree?: boolean; glutenFree?: boolean; vegan?: boolean;
  vegetarian?: boolean; maxReadyInMinutes?: number; minRating?: number;
}

export const SORT_KEYS = ['newest', 'most-rated', 'top-rated', 'most-comments'] as const;
export type SortKey = (typeof SORT_KEYS)[number];

export interface IngredientCreate { ingredientName: string; originalText: string; }
export interface RecipeCreate {
  title: string; servings: number | null; readyInMinutes: number | null;
  cookingMinutes: number | null; preparationMinutes: number | null;
  instructions: string | null;
  instructionsSteps: InstructionStep[]; dairyFree: boolean; glutenFree: boolean;
  vegan: boolean; vegetarian: boolean; cuisines: string[]; ingredients: IngredientCreate[];
  photos: string[];
}
