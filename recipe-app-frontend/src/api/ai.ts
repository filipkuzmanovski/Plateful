import { api, apiVoid } from './client';
import { PAGE_SIZE } from './recipes';
import type {
  AiUsage, DailyMeals, GenerateResult, GeneratedRecipe, GeneratedRecipeSummary, MealType, Page,
} from '@/lib/types';

const BASE = '/api/users/me';

export interface GenerateBody { ingredients: string[]; mealType: MealType | null; onlyWhatIHave: boolean; }

export const aiRecipeQueryKey = (id: string) => ['aiRecipe', id] as const;
export const aiSavedQueryKey = (userId: string | undefined, page: number) => ['aiSaved', userId, page] as const;
export const aiUsageQueryKey = (userId: string | undefined) => ['aiUsage', userId] as const;
export const dailyMealsQueryKey = (userId: string | undefined, date: string) => ['dailyMeals', userId, date] as const;

/** Can take up to a minute: the AI call happens inside the request. */
export const generateRecipe = (body: GenerateBody) =>
  api<GenerateResult>(`${BASE}/ai-recipes/generate`, { method: 'POST', body: JSON.stringify(body) });

export const getAiRecipe = (id: string) => api<GeneratedRecipe>(`${BASE}/ai-recipes/${encodeURIComponent(id)}`);

export const listSavedAiRecipes = (page: number, size: number = PAGE_SIZE): Promise<Page<GeneratedRecipeSummary>> =>
  api(`${BASE}/ai-recipes?${new URLSearchParams({ page: String(page), size: String(size) })}`);

export const saveAiRecipe = (id: string) =>
  apiVoid(`${BASE}/ai-recipes/${encodeURIComponent(id)}/saved`, { method: 'PUT' });

export const unsaveAiRecipe = (id: string) =>
  apiVoid(`${BASE}/ai-recipes/${encodeURIComponent(id)}/saved`, { method: 'DELETE' });

export const getAiUsage = () => api<AiUsage>(`${BASE}/ai-recipes/usage`);

/** `date` is the browser's local date, `YYYY-MM-DD`. Can take up to a minute the first time each day. */
export const getDailyMeals = (date: string) =>
  api<DailyMeals>(`${BASE}/daily-meals?${new URLSearchParams({ date })}`);
