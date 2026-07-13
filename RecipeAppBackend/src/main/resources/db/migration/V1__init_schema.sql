-- =========================================================
-- Recipe Browser — Flyway Migration V1: Initial Schema
-- =========================================================

-- USERS
-- Profile table linked 1:1 to Supabase Auth's auth.users.
-- No password column here — Supabase Auth owns credentials and sessions.
-- Populate this row via a trigger on auth.users insert (or from your
-- Spring Boot signup flow) once auth is wired up.
CREATE TABLE users (
                       id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
                       first_name VARCHAR(255) NOT NULL,
                       last_name VARCHAR(255) NOT NULL,
                       email VARCHAR(255) UNIQUE NOT NULL,
                       created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- RECIPES
CREATE TABLE recipes (
                         id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                         user_id UUID REFERENCES users(id) ON DELETE SET NULL ON UPDATE CASCADE,

    -- where this recipe came from
                         source TEXT NOT NULL DEFAULT 'user' CHECK (source IN ('user', 'spoonacular')),
                         spoonacular_id BIGINT UNIQUE, -- null for user-created recipes; dedup key for imports

                         title VARCHAR(255) NOT NULL,
                         image TEXT,
                         servings SMALLINT,
                         ready_in_minutes SMALLINT,
                         cooking_minutes SMALLINT,
                         preparation_minutes SMALLINT,
                         source_name VARCHAR(255),
                         source_url TEXT,

    -- Instructions: raw text kept as a fallback (some recipes only ever have this),
    -- structured steps used for the real step-by-step UI.
    -- Maps to the API's analyzedInstructions[].steps -> [{number, step, ingredients[], equipment[]}]
    -- Note: analyzedInstructions can come back as an empty array even when raw
    -- `instructions` text is present, so keep both rather than dropping the text field.
                         instructions TEXT,
                         instructions_steps JSONB NOT NULL DEFAULT '[]',

    -- diet flags (1:1 with the recipe, so plain columns rather than a join table)
                         dairy_free BOOLEAN NOT NULL DEFAULT false,
                         gluten_free BOOLEAN NOT NULL DEFAULT false,
                         vegan BOOLEAN NOT NULL DEFAULT false,
                         vegetarian BOOLEAN NOT NULL DEFAULT false,

    -- cuisine tags — filtered on directly; validate against a fixed list in Spring Boot
                         cuisines TEXT[] NOT NULL DEFAULT '{}',

    -- nutrition: the 4 macros pulled out as real columns so you can sort/filter
    -- ("high protein", "under 500 calories"); everything else (vitamins, minerals,
    -- full nutrient breakdown) stored as-is from the API, display-only.
                         calories NUMERIC(7,2),
                         protein_grams NUMERIC(7,2),
                         fat_grams NUMERIC(7,2),
                         carbs_grams NUMERIC(7,2),
                         nutrition JSONB,

                         created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                         updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
--INDEXES FOR RECIPES TABLE
CREATE INDEX idx_recipes_user_id ON recipes (user_id); --displaying recipes that the user has published
CREATE INDEX idx_recipes_cuisines ON recipes USING GIN (cuisines); --displaying recipes with a specific cuisine
CREATE INDEX idx_recipes_calories ON recipes (calories); --displaying recipes for a specific calorie range
CREATE INDEX idx_recipes_protein ON recipes (protein_grams); --displaying recipes for a specific protein range

-- RECIPE INGREDIENTS
-- Freeform by design: users type ingredients their own way, no fixed unit system.
CREATE TABLE recipe_ingredients (
                                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                    recipe_id UUID NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
                                    ingredient_name TEXT NOT NULL,   -- e.g. "garlic" — light filtering/search hook
                                    original_text TEXT NOT NULL,     -- e.g. "3-4 cloves garlic, minced" — shown as-is
                                    sort_order SMALLINT NOT NULL
);
-- INDEXES FOR RECIPE INGREDIENTS
CREATE INDEX idx_recipe_ingredients_recipe_id ON recipe_ingredients (recipe_id); --get ingredients for a recipe

-- RATINGS — one per user per recipe (re-rating updates the existing row)
CREATE TABLE recipe_ratings (
                                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                recipe_id UUID NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
                                user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
                                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                UNIQUE (recipe_id, user_id) -- One rating per user updating the rating changes it
);

-- INDEXES FOR RECIPE RATINGS
CREATE INDEX idx_recipe_ratings_recipe_id ON recipe_ratings (recipe_id);--get ratings for a recipe

-- COMMENTS — many per user allowed
CREATE TABLE recipe_comments (
                                 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                 recipe_id UUID NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
                                 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                 body TEXT NOT NULL,
                                 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- INDEXES FOR RECIPE COMMENTS
CREATE INDEX idx_recipe_comments_recipe_id ON recipe_comments (recipe_id);--get comments for a recipe

-- COMMENT REACTIONS (like/dislike) — one per user per comment
CREATE TABLE comment_reactions (
                                   id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                   comment_id UUID NOT NULL REFERENCES recipe_comments(id) ON DELETE CASCADE,
                                   user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                   is_like BOOLEAN NOT NULL, -- true = like, false = dislike
                                   created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                   UNIQUE (comment_id, user_id) -- one reaction per user per comment; switching like<->dislike updates this row
);

CREATE INDEX idx_comment_reactions_comment_id ON comment_reactions (comment_id);


--Row level security so that no person can access our rows thru the public api key for now untill we have implemented the logic
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE recipes ENABLE ROW LEVEL SECURITY;
ALTER TABLE recipe_ingredients ENABLE ROW LEVEL SECURITY;
ALTER TABLE recipe_ratings ENABLE ROW LEVEL SECURITY;
ALTER TABLE recipe_comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE comment_reactions ENABLE ROW LEVEL SECURITY;
