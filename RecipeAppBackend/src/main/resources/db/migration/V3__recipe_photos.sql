CREATE TABLE recipe_photos (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipe_id UUID NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    path      TEXT NOT NULL,
    position  SMALLINT NOT NULL CHECK (position BETWEEN 0 AND 5),
    UNIQUE (recipe_id, position)
);
CREATE INDEX idx_recipe_photos_recipe ON recipe_photos(recipe_id);
