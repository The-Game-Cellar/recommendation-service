-- Adds raw genre-count map per user library. Row order on /recommendations grouped page
-- must match /profile/statistics: each game contributes +1 to every genre it carries.
-- Distinct from genre_weights (rating-weighted, drives scoring) which biases toward
-- highly-rated genres and would not match the user-facing statistics ordering.

ALTER TABLE user_profiles
    ADD COLUMN library_genre_counts JSONB NOT NULL DEFAULT '{}'::JSONB;
