import { createClient } from '@supabase/supabase-js';

// Auth ONLY. All data goes through our API (src/api) — never Supabase tables.
export const supabase = createClient(
  import.meta.env.VITE_SUPABASE_URL ?? 'http://localhost:54321',
  import.meta.env.VITE_SUPABASE_ANON_KEY ?? 'test-anon-key',
);
