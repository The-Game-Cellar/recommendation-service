-- P2 redesign 2026-05-27: candidate-pool architecture.
-- Worker writes raw-scored candidates with denormalized game data (name, cover, rating,
-- genres, platforms) so request path can apply jitter + shown-penalty + MMR in-memory
-- without hitting game-service. See docs/vault/wiki/PrecomputedRecommendations.md.

DROP TABLE IF EXISTS user_recommendations;

CREATE TABLE user_candidate_pool (
    user_id          VARCHAR(40) NOT NULL,
    igdb_id          INT         NOT NULL,
    base_score       NUMERIC(5,4) NOT NULL,
    tier             SMALLINT    NOT NULL,
    name             TEXT        NOT NULL,
    background_image TEXT,
    rating           NUMERIC(4,2),
    genres           JSONB       NOT NULL DEFAULT '[]'::JSONB,
    platforms        JSONB       NOT NULL DEFAULT '[]'::JSONB,
    computed_at      TIMESTAMP   NOT NULL,
    PRIMARY KEY (user_id, igdb_id)
);

CREATE INDEX idx_pool_user_score ON user_candidate_pool (user_id, base_score DESC);
CREATE INDEX idx_pool_stale ON user_candidate_pool (computed_at);
