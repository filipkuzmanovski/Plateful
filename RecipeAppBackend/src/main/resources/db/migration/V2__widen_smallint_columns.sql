-- =========================================================
-- Recipe Browser — Flyway Migration V2: widen SMALLINT columns to INTEGER
-- =========================================================
-- BUGFIX (review issue #1, smallint/Integer mismatch):
-- V1 declared several columns as SMALLINT while the JPA entities map them
-- as Java Integer. With spring.jpa.hibernate.ddl-auto=validate, Hibernate
-- compares the DB column type against the type it expects for the entity
-- field, and "found smallint, expected integer" fails startup.
--
-- V1 is deliberately NOT edited — Flyway checksums every applied migration,
-- and changing an already-applied file makes Flyway refuse to start. New
-- schema changes always go in a NEW versioned migration like this one.
--
-- The storage saving of SMALLINT (2 bytes vs 4) is irrelevant at this scale,
-- so the simplest correct fix is to widen the columns to match the entities.
-- Existing CHECK constraints (e.g. rating BETWEEN 1 AND 5) survive an
-- ALTER TYPE to a compatible type.

ALTER TABLE recipes
    ALTER COLUMN servings TYPE INTEGER,
    ALTER COLUMN ready_in_minutes TYPE INTEGER,
    ALTER COLUMN cooking_minutes TYPE INTEGER,
    ALTER COLUMN preparation_minutes TYPE INTEGER;

ALTER TABLE recipe_ingredients
    ALTER COLUMN sort_order TYPE INTEGER;

ALTER TABLE recipe_ratings
    ALTER COLUMN rating TYPE INTEGER;
