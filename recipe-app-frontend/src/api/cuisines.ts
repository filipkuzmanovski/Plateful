import { api } from './client';

export interface CuisineInfo {
  key: string;
  label: string;
  region: string;
  seas: string[];
  count: number;
}

export const cuisinesQueryKey = ['cuisines'] as const;

export const getCuisines = () => api<CuisineInfo[]>('/api/recipes/cuisines');

export interface SeaInfo {
  key: string;
  label: string;
  vibe: string;
  starIngredients: string[];
  iconDishes: string[];
}

export const seasQueryKey = ['seas'] as const;

export const getSeas = () => api<SeaInfo[]>('/api/recipes/seas');
