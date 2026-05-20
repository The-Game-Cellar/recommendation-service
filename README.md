# Recommendation Service

> Stateless content-based recommender. Builds a multi-dimensional user profile (genres + tags + release years) from rating evidence blended with declared preferences, scores catalog candidates, and returns ranked results across three tiers.

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen)
![License](https://img.shields.io/badge/License-MIT-blue)

**Port:** `8083` &nbsp;|&nbsp; **Database:** none (stateless)

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

The service is stateless: no database, no caching. Every request fans out to Library Service (for the user's rated games, owned set, platforms, declared preferences) and to Game Service (for the candidate pool: popular by platform, search by genre, game detail).

## Tech Stack

- Java 17, Spring Boot 4.0
- Spring Security OAuth 2 Resource Server for JWT validation
- `RestTemplate` for inter-service calls (forwards the user's JWT to Library Service)
- Pure-function algorithm classes (`UserProfileBuilder`, `SimilarityScorer`, `MMRReRanker`, `TierSelector`)

## Algorithm

### Tier selection

Tier is chosen by the number of **rated** games (not just owned):

```
ratedGames = libraryService.getRatedGames(userId)

ratedGames.size >= 5  -> Tier 1 (personalized)
ratedGames.size >= 1  -> Tier 2 (genre-filtered popular)
ratedGames.size == 0  -> Tier 3 (platform fallback)
```

### Tier 1 — Personalized (5+ rated)

1. Build a weighted genre profile from rated games.
2. Search Game Service for the top 8 genres by profile weight (cache first).
3. Deduplicate, filter by user platforms, exclude already-owned games.
4. Score each candidate (`SimilarityScorer`): `+2.0` per matched genre, `+1.0` bonus when the user's weighted genre rating is >= 8.0, plus a platform-boost term scaled by `EPSILON × platformBoost(candidate, profile.platforms)`.
5. Sort by score descending, shuffle the top 20 for variety, return up to `limit` (default 10).
6. Empty candidate set after filtering -> fall back to Tier 3.

### Tier 2 — Genre-filtered popular (1–4 rated)

Search popular games for genres seen in the user's rated set (capped at 5 genres). Filter, score, return.

### Tier 3 — Platform fallback (0 rated)

Popular games filtered to the user's onboarding platforms, with their owned games excluded. Solves the cold-start problem on first login.

### Preference blend (cold-start signal)

`UserProfileBuilder.buildMultiDim` blends declared preferences (genre / tag / release-year) with rating evidence. Both dimensions share the same decay shape:

```
alpha = min(1 - PREFERENCE_BLEND_FLOOR, ratedCount / PREFERENCE_BLEND_CAP)
profile_dim = alpha × rating_evidence + (1 - alpha) × preference_prior
```

- `PREFERENCE_BLEND_CAP = 10` — rated-count at which the prior decays to its floor weight.
- `PREFERENCE_BLEND_FLOOR = 0.15` — permanent floor share of the prior, so declared preferences never decay to zero.

Preferences are fetched per-request from Library Service. If the call fails, the blend silently degrades to ratings-only (no 5xx propagation from the blend layer).

### Wild Card

Ignores the user profile entirely. Filters: user platforms + exclude owned. Shuffles the top 20 on every call to guarantee variety. Purpose: discovery outside the user's comfort zone.

### Because You Liked X

Selects one seed game from rated >= 8 (shuffled so the seed rotates). Scores candidates by genre overlap with that seed, filters by platform + owned, returns with the label "Because you liked {gameName}".

## API Endpoints

All endpoints require JWT. `userId` is extracted from `@AuthenticationPrincipal Jwt jwt`.

| Method | Path                                                       | Description                                                                                          |
|--------|------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| GET    | `/api/v1/recommendations/personalized?limit={n}`           | Three-tier personalized recommendations.                                                             |
| GET    | `/api/v1/recommendations/wildcard?limit={n}`               | Random discovery outside the comfort zone.                                                           |
| GET    | `/api/v1/recommendations/similar/{gameId}`                 | Similar games to a given game.                                                                       |
| GET    | `/api/v1/recommendations/because-you-liked/{gameId}`       | Recommendations anchored to a specific game.                                                         |
| POST   | `/api/v1/recommendations/dashboard`                        | Combined dashboard payload. Body `{ recentlyShownIds?: number[] }` feeds the soft recency penalty.   |

## Service Communication

The service forwards the user's `Authorization: Bearer {token}` header to Library Service so the downstream identifies the user. Game Service requires no auth internally.

Read paths:

- Library Service: rated games, full owned set (for exclusion), user platforms, declared preferences.
- Game Service: popular games per platform, search by genre, game detail.

## Configuration

| Variable                          | Default                                          | Purpose                                          |
|-----------------------------------|--------------------------------------------------|--------------------------------------------------|
| `RECOMMENDATION_SERVICE_PORT`     | `8083`                                           | Service port                                     |
| `KEYCLOAK_ISSUER_URI`             | `http://localhost:8080/realms/game-cellar`       | JWT issuer                                       |
| `GAME_SERVICE_URL`                | `http://localhost:8081`                          | Game Service base URL                            |
| `LIBRARY_SERVICE_URL`             | `http://localhost:8082`                          | Library Service base URL                         |
| `GAME_SERVICE_CONNECT_TIMEOUT`    | `2000`                                           | HTTP connect timeout in ms                       |
| `GAME_SERVICE_READ_TIMEOUT`       | `8000`                                           | HTTP read timeout in ms (longer for fan-out)     |

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
- Stateless. No cached recommendations. Each dashboard load fans out N inter-service calls (acceptable at MVP scale; tracked as a post-MVP improvement).

## License

[MIT](./LICENSE)
