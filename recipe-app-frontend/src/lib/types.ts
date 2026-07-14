export interface UserDisplay { id: string; firstName: string; lastName: string; }
export interface IngredientDisplay { ingredientName: string; originalText: string; }
export interface InstructionStep { number: number; step: string; ingredients: string[]; equipment: string[]; }

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
}

export interface CommentDisplay { id: string; user: UserDisplay; body: string; likeCount: number; dislikeCount: number; createdAt: string | null; }
export interface RatingDisplay { id: string; user: UserDisplay; rating: number; createdAt: string | null; }
export interface ReactionDisplay { user: UserDisplay; isLike: boolean; createdAt: string | null; }

export interface Page<T> { content: T[]; page: { size: number; number: number; totalElements: number; totalPages: number }; }

export interface RecipeFilter {
  cuisine?: string; dairyFree?: boolean; glutenFree?: boolean; vegan?: boolean;
  vegetarian?: boolean; maxReadyInMinutes?: number; minRating?: number;
}

export interface IngredientCreate { ingredientName: string; originalText: string; }
export interface RecipeCreate {
  title: string; image: string | null; servings: number | null; readyInMinutes: number | null;
  cookingMinutes: number | null; preparationMinutes: number | null;
  sourceName: string | null; sourceUrl: string | null; instructions: string | null;
  instructionsSteps: InstructionStep[]; dairyFree: boolean; glutenFree: boolean;
  vegan: boolean; vegetarian: boolean; cuisines: string[]; ingredients: IngredientCreate[];
}
