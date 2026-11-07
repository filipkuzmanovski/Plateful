-- Reports and instant publishing: recipes go live at once; reported items can be hidden.
-- Recipes waiting for review go live at the switch.
UPDATE recipes
   SET status = 'approved', ever_approved = true, approved_title = title,
       reviewed_at = now(), rejection_reason = NULL
 WHERE status = 'pending';

ALTER TABLE recipes         ADD COLUMN hidden_at TIMESTAMPTZ;
ALTER TABLE recipe_comments ADD COLUMN hidden_at TIMESTAMPTZ;

CREATE TABLE content_reports (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipe_id   UUID REFERENCES recipes(id)         ON DELETE CASCADE,
    comment_id  UUID REFERENCES recipe_comments(id) ON DELETE CASCADE,
    reason      TEXT NOT NULL
                CHECK (reason IN ('spam', 'offensive', 'unsafe', 'not_food', 'other')),
    note        VARCHAR(500),
    state       TEXT NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'dismissed')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((recipe_id IS NULL) <> (comment_id IS NULL)),
    UNIQUE (reporter_id, recipe_id),
    UNIQUE (reporter_id, comment_id)
);
CREATE INDEX idx_reports_open_recipe  ON content_reports (recipe_id)  WHERE state = 'open';
CREATE INDEX idx_reports_open_comment ON content_reports (comment_id) WHERE state = 'open';
CREATE INDEX idx_reports_reporter_day ON content_reports (reporter_id, created_at);
ALTER TABLE content_reports ENABLE ROW LEVEL SECURITY;
