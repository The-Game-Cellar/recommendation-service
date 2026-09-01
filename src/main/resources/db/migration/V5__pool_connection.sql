-- The connection behind each candidate, computed with the pool and aging with it: the rated
-- game it overlaps most (rating 7 or higher, two or more shared genres / themes / tags) and
-- the shared features that carry the most weight in the user's profile. A NULL seed means no
-- rated game qualified; an empty shared_tags means the sentence falls back to the tier reason.

ALTER TABLE user_candidate_pool
    ADD COLUMN seed_igdb_id INT,
    ADD COLUMN seed_name    TEXT,
    ADD COLUMN seed_rating  SMALLINT,
    ADD COLUMN shared_tags  JSONB NOT NULL DEFAULT '[]'::JSONB;
