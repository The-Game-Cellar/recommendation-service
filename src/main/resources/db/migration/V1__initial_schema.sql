-- P2 Phase 2 baseline schema for rec_db.
-- Backs precomputed recommendations + user profile cache + worker compute queue.
-- See docs/vault/wiki/PrecomputedRecommendations.md for the locked design.

CREATE TABLE user_recommendations (
    user_id     VARCHAR(40) NOT NULL,
    igdb_id     INT         NOT NULL,
    score       NUMERIC(5,4) NOT NULL,
    tier        SMALLINT    NOT NULL,
    rank        SMALLINT    NOT NULL,
    computed_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (user_id, igdb_id)
);

-- Dashboard / personalized read path: WHERE user_id = ? ORDER BY tier ASC, score DESC LIMIT N.
CREATE INDEX idx_user_recommendations_user_tier_score
    ON user_recommendations (user_id, tier ASC, score DESC);

-- Hourly TTL safety-net scanner: WHERE computed_at < now() - interval '24 hours'.
CREATE INDEX idx_user_recommendations_stale
    ON user_recommendations (computed_at);


CREATE TABLE user_profiles (
    user_id          VARCHAR(40) PRIMARY KEY,
    genre_weights    JSONB       NOT NULL DEFAULT '{}'::JSONB,
    tag_weights      JSONB       NOT NULL DEFAULT '{}'::JSONB,
    platform_weights JSONB       NOT NULL DEFAULT '{}'::JSONB,
    decade_weights   JSONB       NOT NULL DEFAULT '{}'::JSONB,
    rated_count      INT         NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP   NOT NULL
);


-- Deduplication queue for per-user worker. Library-write events upsert user_id; worker
-- dequeues with SELECT ... FOR UPDATE SKIP LOCKED so future horizontal scaling stays safe.
CREATE TABLE compute_queue (
    user_id   VARCHAR(40) PRIMARY KEY,
    queued_at TIMESTAMP   NOT NULL,
    attempts  SMALLINT    NOT NULL DEFAULT 0
);

CREATE INDEX idx_compute_queue_queued_at ON compute_queue (queued_at);
