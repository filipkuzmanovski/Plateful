ALTER TABLE generated_recipes DROP CONSTRAINT generated_recipes_kind_check;
ALTER TABLE generated_recipes ADD CONSTRAINT generated_recipes_kind_check
    CHECK (kind IN ('cook', 'daily', 'post'));
