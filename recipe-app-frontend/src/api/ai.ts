import { api, apiVoid } from './client';
import { PAGE_SIZE } from './recipes';
import type {
  AiUsage, DailyMeals, GenerateResult, GeneratedRecipe, GeneratedRecipeSummary, MealType, Page, ScanResult,
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

/** Sends 1 to 3 shrunken JPEGs as `photos` parts; the browser sets the multipart Content-Type. Can take 30 s or more. */
export const scanPhotos = (photos: Blob[]) => {
  const form = new FormData();
  photos.forEach((blob, i) => form.append('photos', blob, `photo-${i + 1}.jpg`));
  return api<ScanResult>(`${BASE}/ai-recipes/scan`, { method: 'POST', body: form });
};

/**
 * Sends the pasted caption (only when not blank) and 0 to 2 shrunken JPEGs as `screenshots` parts; the browser
 * sets the multipart Content-Type. Can take up to a minute: the AI reads and writes inside the request.
 */
export const importPost = (caption: string | null, screenshots: Blob[]) => {
  const form = new FormData();
  if (caption && caption.trim()) form.append('caption', caption);
  screenshots.forEach((blob, i) => form.append('screenshots', blob, `screenshot-${i + 1}.jpg`));
  return api<GenerateResult>(`${BASE}/ai-recipes/import`, { method: 'POST', body: form });
};

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
