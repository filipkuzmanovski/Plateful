-- Row level security so the public api key cannot read or delete anyone's saves, same as the V1 tables.
ALTER TABLE saved_recipes ENABLE ROW LEVEL SECURITY;
