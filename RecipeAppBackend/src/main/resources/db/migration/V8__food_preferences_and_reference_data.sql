CREATE TABLE user_preferences (
    user_id              UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    allergens            TEXT[] NOT NULL DEFAULT '{}',  -- allergen keys from food-rules.csv
    diets                TEXT[] NOT NULL DEFAULT '{}',  -- vegetarian | vegan | gluten_free | dairy_free
    disliked_ingredients TEXT[] NOT NULL DEFAULT '{}',  -- normalised names
    liked_ingredients    TEXT[] NOT NULL DEFAULT '{}',  -- normalised names
    liked_cuisines       TEXT[] NOT NULL DEFAULT '{}',  -- keys from cuisines.csv
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reference_recipes (
    id               BIGINT PRIMARY KEY,               -- RecipeNLG row id
    title            VARCHAR(255) NOT NULL,
    ingredient_lines TEXT[] NOT NULL,                  -- with quantities, as in the dataset
    ingredient_names TEXT[] NOT NULL,                  -- normalised, from the NER column
    directions       TEXT[] NOT NULL,
    meal_types       TEXT[] NOT NULL DEFAULT '{}',     -- breakfast | lunch | dinner
    allergens        TEXT[] NOT NULL DEFAULT '{}',     -- allergen keys found, computed at import
    vegetarian       BOOLEAN NOT NULL,
    vegan            BOOLEAN NOT NULL,
    gluten_free      BOOLEAN NOT NULL,
    dairy_free       BOOLEAN NOT NULL
);
CREATE INDEX idx_reference_recipes_names ON reference_recipes USING GIN (ingredient_names);
CREATE INDEX idx_reference_recipes_meals ON reference_recipes USING GIN (meal_types);

CREATE TABLE ingredient_stats (
    name         TEXT PRIMARY KEY,                     -- normalised
    recipe_count INTEGER NOT NULL                      -- over all 2.2 million recipes
);
CREATE INDEX idx_ingredient_stats_prefix ON ingredient_stats (name text_pattern_ops);

CREATE TABLE ingredient_pairings (
    ingredient     TEXT NOT NULL,
    partner        TEXT NOT NULL,
    together_count INTEGER NOT NULL,
    PRIMARY KEY (ingredient, partner)
);

ALTER TABLE user_preferences    ENABLE ROW LEVEL SECURITY;
ALTER TABLE reference_recipes   ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingredient_stats    ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingredient_pairings ENABLE ROW LEVEL SECURITY;
