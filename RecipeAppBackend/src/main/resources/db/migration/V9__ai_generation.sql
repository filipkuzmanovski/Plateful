CREATE TABLE generated_recipes (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind           TEXT NOT NULL CHECK (kind IN ('cook', 'daily')),
    meal_type      TEXT CHECK (meal_type IN ('breakfast', 'lunch', 'dinner')),
    title          VARCHAR(255) NOT NULL,
    recipe         JSONB NOT NULL,
    request        JSONB NOT NULL,
    provider       TEXT NOT NULL,
    model          TEXT NOT NULL,
    novelty_score  NUMERIC(4,3) NOT NULL,
    reference_ids  BIGINT[] NOT NULL DEFAULT '{}',
    saved_at       TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_generated_recipes_saved ON generated_recipes (user_id, saved_at DESC) WHERE saved_at IS NOT NULL;
CREATE INDEX idx_generated_recipes_created ON generated_recipes (created_at);

CREATE TABLE daily_meal_sets (
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    meal_date    DATE NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('generating', 'ready', 'failed')),
    breakfast_id UUID REFERENCES generated_recipes(id),
    lunch_id     UUID REFERENCES generated_recipes(id),
    dinner_id    UUID REFERENCES generated_recipes(id),
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, meal_date)
);

CREATE TABLE ai_usage (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    usage_date  DATE NOT NULL,
    generations INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, usage_date)
);

ALTER TABLE generated_recipes ENABLE ROW LEVEL SECURITY;
ALTER TABLE daily_meal_sets   ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_usage          ENABLE ROW LEVEL SECURITY;
