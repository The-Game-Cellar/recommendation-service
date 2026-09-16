-- The seed's rating is the user's own rating of that game, which moves in half steps since
-- library-service widened it, so the sentence "Because you rated Hades 9.5" needs the half kept
-- here too. Whole numbers already stored cast as they are.

ALTER TABLE user_candidate_pool ALTER COLUMN seed_rating TYPE NUMERIC(3,1);
