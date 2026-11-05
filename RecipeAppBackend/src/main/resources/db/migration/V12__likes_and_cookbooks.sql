CREATE TABLE recipe_likes (
    user_id    UUID NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    recipe_id  UUID NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, recipe_id)
);
CREATE INDEX idx_recipe_likes_user_created ON recipe_likes (user_id, created_at DESC);
CREATE INDEX idx_recipe_likes_recipe ON recipe_likes (recipe_id);

CREATE TABLE cookbooks (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind       TEXT NOT NULL DEFAULT 'liked' CHECK (kind IN ('liked')),
    volume     INTEGER NOT NULL CHECK (volume > 0),
    subtitle   VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, volume)
);

CREATE TABLE cookbook_recipes (
    cookbook_id UUID NOT NULL REFERENCES cookbooks(id) ON DELETE CASCADE,
    recipe_id   UUID NOT NULL REFERENCES recipes(id)   ON DELETE CASCADE,
    position    SMALLINT NOT NULL CHECK (position > 0),
    PRIMARY KEY (cookbook_id, recipe_id),
    UNIQUE (cookbook_id, position)
);

-- Row level security so the public api key cannot read or change these rows, same as saved_recipes.
ALTER TABLE recipe_likes     ENABLE ROW LEVEL SECURITY;
ALTER TABLE cookbooks        ENABLE ROW LEVEL SECURITY;
ALTER TABLE cookbook_recipes ENABLE ROW LEVEL SECURITY;
