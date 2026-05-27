-- Per-genre top-up support. Holding table parks recently-served candidates so the next
-- per-genre refill fetches strictly fresh ids; entries expire after a short window so the
-- catalog can re-surface them. target_genre on compute_queue lets the worker distinguish
-- full-replace jobs (NULL) from per-genre top-ups (genre name set).

CREATE TABLE pool_holding (
    user_id     VARCHAR(40) NOT NULL,
    genre       VARCHAR(100) NOT NULL,
    igdb_id     INT          NOT NULL,
    released_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (user_id, genre, igdb_id)
);

CREATE INDEX idx_pool_holding_release ON pool_holding (released_at);
CREATE INDEX idx_pool_holding_user_genre ON pool_holding (user_id, genre);

ALTER TABLE compute_queue
    ADD COLUMN target_genre VARCHAR(100);
