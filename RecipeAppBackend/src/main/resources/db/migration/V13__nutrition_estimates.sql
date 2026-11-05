CREATE TABLE nutrition_estimates (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipe_id           UUID UNIQUE REFERENCES recipes(id)           ON DELETE CASCADE,
    generated_recipe_id UUID UNIQUE REFERENCES generated_recipes(id) ON DELETE CASCADE,
    status              TEXT NOT NULL CHECK (status IN ('ready', 'failed')),
    calories            NUMERIC(8,1),
    protein_grams       NUMERIC(8,1),
    fat_grams           NUMERIC(8,1),
    carbs_grams         NUMERIC(8,1),
    breakdown           JSONB,
    ingredients_hash    TEXT NOT NULL,
    provider            TEXT,
    model               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((recipe_id IS NULL) <> (generated_recipe_id IS NULL)),
    CHECK (status = 'failed' OR (calories IS NOT NULL AND protein_grams IS NOT NULL
                                 AND fat_grams IS NOT NULL AND carbs_grams IS NOT NULL
                                 AND breakdown IS NOT NULL))
);
CREATE INDEX idx_nutrition_estimates_created ON nutrition_estimates (created_at);
ALTER TABLE nutrition_estimates ENABLE ROW LEVEL SECURITY;
