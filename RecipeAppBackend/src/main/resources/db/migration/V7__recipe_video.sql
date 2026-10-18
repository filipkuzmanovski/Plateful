ALTER TABLE recipes
    ADD COLUMN video_path             TEXT,
    ADD COLUMN video_poster_path      TEXT,
    ADD COLUMN video_duration_seconds SMALLINT
        CHECK (video_duration_seconds BETWEEN 1 AND 60),
    ADD CONSTRAINT recipes_video_all_or_none CHECK (
        (video_path IS NULL AND video_poster_path IS NULL AND video_duration_seconds IS NULL)
        OR (video_path IS NOT NULL AND video_poster_path IS NOT NULL AND video_duration_seconds IS NOT NULL)
    );
