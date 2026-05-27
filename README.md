# Recommendation Service

> Content-based recommender. Builds a multi-dimensional user profile (genres + tags + release years) from rating evidence blended with declared preferences, scores catalog candidates, and returns ranked results across three tiers. P2 (in progress) moves scoring from request-path to a background worker backed by `recommendation_db`.

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen)
![License](https://img.shields.io/badge/License-MIT-blue)

**Port:** `8083` &nbsp;|&nbsp; **Database:** `recommendation_db` (Postgres on port 5434) &nbsp;|&nbsp; **Cache:** Redis (6379)

## Responsibilities

- Three-tier personalized recommendations that work from day one (even for users with zero ratings).
- Wild Card: random discovery outside the user's comfort zone.
- "Because You Liked X": similar games anchored to a specific high-rated game.
- Similar games for an arbitrary game ID.
- Combined dashboard data feeding the frontend home page.

## Position in the System

```
Frontend ----via GW----> Recommendation Service (8083)
                               |  (forwards user JWT)
                               +----> Library Service (8082)
                               +----> Game Service (8081)
```

Real-time scoring path (current production behavior on `/personalized`, `/wildcard`, `/dashboard.recommendations`): every request fans out to Library Service (for the user's rated games, owned set, platforms, declared preferences) and to Game Service (for the candidate pool). P2 migration in progress: scoring moves to a background per-user worker that writes precomputed recommendations to `recommendation_db`; the request path will be switched to read from the cache in Phase 4. Phase 1 (Redis introduction) + Phase 2 (`rec_db` schema with Flyway V1) landed 2026-05-26. Phase 3 (per-user worker + shared `RecommendationComputer`) landed 2026-05-27. See `docs/vault/wiki/PrecomputedRecommendations.md`.

## Tech Stack

- Java 17, Spring Boot 4.0
- Spring Security OAuth 2 Resource Server for JWT validation
- Spring Data JPA + Hibernate against Postgres `recommendation_db` (port 5434, Flyway-managed)
- `@Scheduled` per-user worker (fixed thread pool size 5) + `@Transactional` UserComputeProcessor
- `RestTemplate` for inter-service calls (forwards the user's JWT in the request path, X-Internal-Token in the worker path)
- Pure-function algorithm classes (`UserProfileBuilder`, `SimilarityScorer`, `MMRReRanker`, `TierSelector`)
- Testcontainers for repository integration tests (`RecDbRepositoryTest`)

## Algorithm

### Tier selection

Tier is chosen by the number of **rated** games (not just owned):

```
ratedGames = libraryService.getRatedGames(userId)

ratedGames.size >= 5  -> Tier 1 (personalized)
ratedGames.size >= 1  -> Tier 2 (genre-filtered popular)
ratedGames.size == 0  -> Tier 3 (platform fallback)
```

### Tier 1 : Personalized (5+ rated)

1. Build a weighted genre profile from rated games.
2. Search Game Service for the top 8 genres by profile weight (cache first).
3. Deduplicate, filter by user platforms, exclude already-owned games.
4. Score each candidate (`SimilarityScorer`): `+2.0` per matched genre, `+1.0` bonus when the user's weighted genre rating is >= 8.0, plus a platform-boost term scaled by `EPSILON × platformBoost(candidate, profile.platforms)`.
5. Sort by score descending, shuffle the top 20 for variety, return up to `limit` (default 10).
6. Empty candidate set after filtering -> fall back to Tier 3.

### Tier 2 : Genre-filtered popular (1–4 rated)

Search popular games for genres seen in the user's rated set (capped at 5 genres). Filter, score, return.

### Tier 3 : Platform fallback (0 rated)

Popular games filtered to the user's onboarding platforms, with their owned games excluded. Solves the cold-start problem on first login.

### Preference blend (cold-start signal)

`UserProfileBuilder.buildMultiDim` blends declared preferences (genre / tag / release-year) with rating evidence. Both dimensions share the same decay shape:

```
alpha = min(1 - PREFERENCE_BLEND_FLOOR, ratedCount / PREFERENCE_BLEND_CAP)
profile_dim = alpha × rating_evidence + (1 - alpha) × preference_prior
```

- `PREFERENCE_BLEND_CAP = 10` : rated-count at which the prior decays to its floor weight.
- `PREFERENCE_BLEND_FLOOR = 0.15` : permanent floor share of the prior, so declared preferences never decay to zero.

Preferences are fetched per-request from Library Service. If the call fails, the blend silently degrades to ratings-only (no 5xx propagation from the blend layer).

### Wild Card

Ignores the user profile entirely. Filters: user platforms + exclude owned. Shuffles the top 20 on every call to guarantee variety. Purpose: discovery outside the user's comfort zone.

### Because You Liked X

Selects one seed game from rated >= 8 (shuffled so the seed rotates). Scores candidates by genre overlap with that seed, filters by platform + owned, returns with the label "Because you liked {gameName}".

## API Endpoints

All endpoints require JWT. `userId` is extracted from `@AuthenticationPrincipal Jwt jwt`.

| Method | Path                                                       | Description                                                                                          |
|--------|------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| POST   | `/api/v1/recommendations/personalized`                     | Three-tier personalized recommendations served from the pre-computed pool. Body `{ limit, recentlyShownIds[] }`. |
| POST   | `/api/v1/recommendations/personalized/grouped`             | Genre-bucketed rows (up to 8 rows x 15 games + 1 long-tail) from the same pool. Body `{ recentlyShownIds[] }`. |
| POST   | `/api/v1/recommendations/personalized/grouped/genre`       | Per-row refresh button. Returns one fresh row + enqueues per-genre top-up. Body `{ genre, recentlyShownIds[] }`. |
| GET    | `/api/v1/recommendations/wildcard?limit={n}`               | Random discovery from game-service catalog, library + platform filter applied in rec-service.        |
| GET    | `/api/v1/recommendations/similar/{gameId}`                 | Similar games to a given game.                                                                       |
| GET    | `/api/v1/recommendations/because-you-liked/{gameId}`       | Recommendations anchored to a specific game.                                                         |
| GET    | `/api/v1/recommendations/dashboard/because-you-liked?excludeSeedIgdbIds={ids}` | Dashboard refresh for a BYL section. Re-rolls seed excluding currently-shown ids. |
| POST   | `/api/v1/recommendations/dashboard`                        | Combined dashboard payload (recs + 2 BYL sections + wildcard). Body `{ recentlyShownIds?: number[] }`.|
| POST   | `/api/v1/admin/rec/enqueue?userId={uuid}`                  | Admin-only. Upserts a row in `compute_queue` so the per-user worker recomputes on next tick. Requires ROLE_ADMIN. |

## Service Communication

Two paths:

- **Request path (RecommendationService)**: reads the denormalized candidate pool from `rec_db.user_candidate_pool` and applies jitter + shown-penalty + MMR in-memory. No game-service / library-service hop in the warm path. Cold-start (empty pool) and wildcard / because-you-liked still forward the user's `Authorization: Bearer {token}` to game + library service.
- **Worker path (PerUserWorker + UserComputeProcessor + InternalLibraryClient + InternalGameClient)**: no user JWT. Uses the shared `INTERNAL_SERVICE_TOKEN` secret in an `X-Internal-Token` header against `/internal/library/users/{userId}/*` (library-service) and `/internal/games/*` (game-service). Worker computes raw-scored candidates with denormalized game data; the request path layers jitter / shown-penalty / MMR on top.

Read paths:

- Library Service: rated games, full owned set (for exclusion), user platforms, declared preferences (genre / tag / release-year).
- Game Service: popular games per platform, random-quality candidates by genre, game detail (for similar-graph traversal).

## Configuration

| Variable                          | Default                                          | Purpose                                          |
|-----------------------------------|--------------------------------------------------|--------------------------------------------------|
| `RECOMMENDATION_SERVICE_PORT`     | `8083`                                           | Service port                                     |
| `KEYCLOAK_ISSUER_URI`             | `http://localhost:8080/realms/game-cellar`       | JWT issuer                                       |
| `GAME_SERVICE_URL`                | `http://localhost:8081`                          | Game Service base URL                            |
| `LIBRARY_SERVICE_URL`             | `http://localhost:8082`                          | Library Service base URL                         |
| `GAME_SERVICE_CONNECT_TIMEOUT`    | `2000`                                           | HTTP connect timeout in ms                       |
| `GAME_SERVICE_READ_TIMEOUT`       | `8000`                                           | HTTP read timeout in ms (longer for fan-out)     |
| `REC_DB_URL`                      | `jdbc:postgresql://127.0.0.1:5434/recommendation_db` | Postgres connection for precomputed-recs cache |
| `REC_DB_USERNAME`                 | `postgres`                                       | Postgres user                                    |
| `REC_DB_PASSWORD`                 | (required)                                       | Postgres password                                |
| `DDL_AUTO`                        | `validate`                                       | Hibernate DDL mode (validate; schema owned by Flyway) |
| `REDIS_HOST`                      | `localhost`                                      | Redis host (cache + future pub/sub)              |
| `REDIS_PORT`                      | `6379`                                           | Redis port                                       |
| `REDIS_PASSWORD`                  | (required)                                       | Redis password                                   |
| `INTERNAL_SERVICE_TOKEN`          | (required for worker)                            | Shared secret sent as `X-Internal-Token` on worker -> library + game-service `/internal/**` calls |
| `RECOMMENDATION_POOL_SIZE`        | `2500`                                           | Per-user candidate-pool size written by the worker |
| `RECOMMENDATION_REFILL_THRESHOLD_PERCENT` | `50`                                     | Pool depletion percentage that triggers an async refill |
| `RECOMMENDATION_STALE_TTL_HOURS`  | `24`                                             | Pool rows older than this trigger an async refill while still serving current data |
| `RECOMMENDATION_USER_STATE_TTL_SECONDS` | `300`                                      | Redis TTL for the userLibrary + userPlatforms caches (wildcard exclusion) |
| `recommendation.cache.user-profile-ttl-seconds` | `600`                              | Redis TTL for the UserProfileSnapshot cache (Phase 6). Invalidated on library-write events. |
| `recommendation.library-write-subscriber.enabled` | `true`                           | Phase 5 Redis subscriber gate. Disabled in tests to bypass Redis-conn requirement. |
| `recommendation.stale-scan.fixed-delay-ms` | `3600000`                               | StaleScanner hourly interval (Phase 6). |
| `recommendation.stale-scan.initial-delay-ms` | `300000`                              | StaleScanner initial delay after startup (5 min). |
| `recommendation.worker.fixed-delay-ms` | `30000`                                     | Per-user worker scheduler delay between batches (ms) |
| `recommendation.worker.initial-delay-ms` | `30000`                                   | Per-user worker scheduler initial delay after startup (ms) |

## Database

Schema lives under `src/main/resources/db/migration/` and is managed by Flyway. Current schema (V1 + V2 + V3 + V4):

- `user_candidate_pool(user_id, igdb_id, base_score, tier, name, background_image, rating, genres, platforms, computed_at)` with PK on `(user_id, igdb_id)` and indexes on `(user_id, base_score DESC)` + `(computed_at)`. Denormalized so the request path can build response DTOs without a game-service hop. Genres + platforms are JSONB string arrays.
- `user_profiles(user_id, genre_weights, tag_weights, platform_weights, decade_weights, library_genre_counts, rated_count, updated_at)`. JSONB weight columns. `library_genre_counts` (V3) is the raw genre count over the full library, drives the /recommendations row order matching `/profile/statistics`.
- `pool_holding(user_id, genre, igdb_id, released_at)` (V4). Parks recently-evicted bucket ids during a per-genre top-up so the next fetch excludes them. Worker prunes expired rows at each tick.
- `compute_queue(user_id, queued_at, attempts, target_genre)`. `target_genre` (V4) is NULL for full-replace jobs, set for per-genre top-up jobs.
- `compute_queue(user_id, queued_at, attempts)` for the per-user worker.

`game_similarities` (catalog-metadata, not user-bound) lives in `game_db` under game-service, not here. See P2 design doc.

## Run Locally

### Prerequisites

- Java 17+
- A running Library Service on port 8082
- A running Game Service on port 8081
- A running Keycloak on port 8080

### Direct

```bash
./mvnw spring-boot:run
```

### Via Docker Compose

```bash
docker compose up recommendation-service
```

## Tests

```bash
./mvnw test
```

Algorithm classes (`UserProfileBuilder`, `SimilarityScorer`, `TierSelector`, `MMRReRanker`) are pure functions and tested without mocks. Service-layer tests use mocked clients.

## Known Limitations

- Content-based filtering only. No collaborative filtering.
- P2 implementation in progress (Phase 1-3 of 8 done): per-user worker now precomputes into `recommendation_db`, but request endpoints still serve from real-time scoring. The dashboard read-path cache-switch lands in Phase 4. Event-driven invalidation (Phase 5), TTL safety-net + UserProfile cache (Phase 6), and similarity worker relocation (Phases 7-8) follow.

## License

[MIT](./LICENSE)
