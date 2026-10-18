ALTER TABLE users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE recipes
    ADD COLUMN status           TEXT NOT NULL DEFAULT 'pending'
                                CHECK (status IN ('pending', 'approved', 'rejected')),
    ADD COLUMN rejection_reason VARCHAR(500),
    ADD COLUMN approved_title   VARCHAR(255),
    ADD COLUMN ever_approved    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN submitted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN reviewed_at      TIMESTAMPTZ,
    ADD COLUMN reviewed_by      UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN revision         INTEGER NOT NULL DEFAULT 0;

-- Everything that exists today stays public.
UPDATE recipes
   SET status = 'approved', approved_title = title, ever_approved = true, reviewed_at = now();

CREATE INDEX idx_recipes_pending_queue ON recipes (submitted_at) WHERE status = 'pending';
CREATE INDEX idx_recipes_status ON recipes (status);
