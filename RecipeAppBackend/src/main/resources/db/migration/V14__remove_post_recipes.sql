-- The Instagram post import is gone. No post recipes are expected, but any left over would no longer
-- fit the kinds the app knows.
DELETE FROM generated_recipes WHERE kind = 'post';

ALTER TABLE generated_recipes DROP CONSTRAINT generated_recipes_kind_check;
ALTER TABLE generated_recipes ADD CONSTRAINT generated_recipes_kind_check
    CHECK (kind IN ('cook', 'daily'));
