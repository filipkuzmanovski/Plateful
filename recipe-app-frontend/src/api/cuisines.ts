import { api } from './client';

export interface CuisineInfo {
  key: string;
  label: string;
  region: string;
  mediterranean: boolean;
  count: number;
}

export const cuisinesQueryKey = ['cuisines'] as const;

export const getCuisines = () => api<CuisineInfo[]>('/api/recipes/cuisines');
