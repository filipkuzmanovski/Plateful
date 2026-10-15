CREATE TABLE saved_recipes (
    user_id    UUID NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    recipe_id  UUID NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, recipe_id)
);
CREATE INDEX idx_saved_recipes_user_created ON saved_recipes (user_id, created_at DESC);
