package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.algorithm.MMRReRanker;
import com.thegamecellar.recommendationservice.algorithm.RecommendationTier;
import com.thegamecellar.recommendationservice.algorithm.SimilarityScorer;
import com.thegamecellar.recommendationservice.algorithm.TierSelector;
import com.thegamecellar.recommendationservice.algorithm.UserProfile;
import com.thegamecellar.recommendationservice.algorithm.UserProfileBuilder;
import com.thegamecellar.recommendationservice.client.GameServiceClient;
import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private static final int TIER1_RATED_FOR_GRAPH = 8;
    private static final int TIER2_RATED_FOR_GRAPH = 5;
    private static final int MAX_SIMILAR_FETCHES = 30;

    // Per-request stochastic perturbation added to relevance score before sort + MMR.
    // Score range is roughly [0, 1] (weighted-cosine sum), so 0.08 = up to 8% noise: enough
    // to swap positions within a same-quality band but not enough to lift a low-relevance
    // candidate over a strong one.
    private static final double SCORE_JITTER = 0.08;

    // Soft penalty applied to relevance score for games the client has already shown in this
    // session (cleared on logout). 0.40 is large relative to the ~[0, 1] score range; pushes
    // recently-shown candidates well out of the MMR top picks but does NOT exclude them, so
    // the system degrades gracefully if the candidate pool is otherwise exhausted.
    private static final double SHOWN_PENALTY = 0.40;

    // Quality bar passed to Game Service /random-quality so the SQL ORDER BY RANDOM() runs
    // over a pre-filtered subset. IGDB rating scale is 0..10 after the cache-side normalisation
    // (matches the public igdb.com display); 7.0 corresponds to ~70/100 raw IGDB, the same
    // editorial threshold the previous 0..5 gate used. 10-vote floor weeds out games whose
    // averages are statistically meaningless. WildCard and Tier 3 (popular) intentionally
    // bypass this gate.
    private static final java.math.BigDecimal MIN_EFFECTIVE_RATING = new java.math.BigDecimal("7.0");
    private static final int MIN_RATING_COUNT = 10;

    // Grouped /personalized layout. Top N rated genres become rows; each row holds K games.
    // Oversample ratio compensates for the recency / cross-row dedupe filtering: the SQL
    // random-quality call returns OVERSAMPLE × PER_ROW rows so we usually have enough fresh
    // games left after filtering to fill the row.
    public static final int GROUPED_TARGET_ROWS = 8;
    public static final int GROUPED_PER_ROW = 15;
    // Larger random pool per row gives the platform-boost sort meaningful headroom even after
    // the owned + cross-row + recently-shown filters thin the candidate set. Earlier 40 left
    // too few primary-platform candidates for users with deep collections on a single platform
    // (catalog distribution is naturally PC-skewed in genres like Adventure / Indie / Strategy).
    private static final int GROUPED_OVERSAMPLE = 150;

    private final GameServiceClient gameServiceClient;
    private final LibraryServiceClient libraryServiceClient;

    public List<RecommendationDTO> getPersonalized(String bearerToken, int limit) {
        return getPersonalized(bearerToken, limit, null);
    }

    /**
     * Row-based recommendations. Tier 1 / Tier 2 users get up to {@link #GROUPED_TARGET_ROWS}
     * genre-titled rows ranked by their rated-library composition; Tier 3 (no rated games) gets
     * a single "Popular" row plus an onboarding nudge. Empty genre rows cascade to the next
     * priority genre; once user genres are exhausted, popular fallback rows fill the remainder.
     */
    public com.thegamecellar.recommendationservice.model.dto.GroupedRecommendationsResponse getPersonalizedGrouped(
            String bearerToken,
            Set<Integer> recentlyShownIds) {
        List<UserGameDTO> allGames = Objects.requireNonNullElseGet(libraryServiceClient.getGames(bearerToken), List::of);
        List<UserGameDTO> ratedGames = allGames.stream()
                .filter(g -> g.getRating() != null)
                .filter(g -> isEligibleStatus(g.getStatus()))
                .toList();

        Set<Integer> ownedGameIds = allGames.stream()
                .map(UserGameDTO::getIgdbGameId)
                .collect(Collectors.toSet());

        Set<Integer> recentlyShown = recentlyShownIds == null ? Set.of() : recentlyShownIds;

        List<UserPlatformDTO> platformList = Objects.requireNonNullElseGet(libraryServiceClient.getPlatforms(bearerToken), List::of);
        Set<String> userPlatforms = platformList.stream()
                .map(UserPlatformDTO::getPlatformName)
                .collect(Collectors.toSet());

        RecommendationTier tier = TierSelector.select(ratedGames.size());

        if (tier == RecommendationTier.THREE) {
            return buildTier3Grouped(ownedGameIds, recentlyShown, userPlatforms, bearerToken);
        }
        return buildTier12Grouped(ratedGames, ownedGameIds, recentlyShown, userPlatforms, platformList, bearerToken, tier);
    }

    private com.thegamecellar.recommendationservice.model.dto.GroupedRecommendationsResponse buildTier3Grouped(
            Set<Integer> ownedGameIds,
            Set<Integer> recentlyShown,
            Set<String> userPlatforms,
            String bearerToken) {
        List<RecommendationDTO> popular = getTier3(ownedGameIds, recentlyShown, userPlatforms, GROUPED_PER_ROW, bearerToken);
        com.thegamecellar.recommendationservice.model.dto.RecommendationRow row =
                com.thegamecellar.recommendationservice.model.dto.RecommendationRow.builder()
                        .label(userPlatforms.isEmpty() ? "Popular games" : "Popular on your platforms")
                        .fallback(true)
                        .games(popular)
                        .build();
        return com.thegamecellar.recommendationservice.model.dto.GroupedRecommendationsResponse.builder()
                .rows(List.of(row))
                .tier(3)
                .emptyMessage("Rate games in your library to unlock personalized recommendations.")
                .build();
    }

    private com.thegamecellar.recommendationservice.model.dto.GroupedRecommendationsResponse buildTier12Grouped(
            List<UserGameDTO> ratedGames,
            Set<Integer> ownedGameIds,
            Set<Integer> recentlyShown,
            Set<String> userPlatforms,
            List<UserPlatformDTO> platformList,
            String bearerToken,
            RecommendationTier tier) {
        // Sqrt-normalised platform profile derived from rated games. Feeds the per-row
        // platform-boost ordering inside buildGenreRow so the grouped layout reflects platform
        // skew the same way the flat /personalized layout does. The platformList carries the
        // is_primary flag so a primary-marked platform gets its raw count multiplied before
        // normalisation. Empty when the user has no rated games with a platform value
        // (degrades to no-op via platformBoost = 0).
        Map<String, Double> platformProfile = UserProfileBuilder.buildMultiDim(ratedGames, platformList).platforms();
        // Genre priority: fractional weighting across the user's RATED games. Each rated game
        // contributes 1.0 vote total, split evenly across its genres (a 4-genre AAA contributes
        // 0.25 to each, a 1-genre indie contributes 1.0 to its single genre). Normalises against
        // IGDB's varying tag-bredd so broad themes like Action / Horror / Fantasy don't drown
        // out narrow user preferences (Stealth, Metroidvania, Roguelike) just because they
        // appear on more catalog rows. Background: rated set already filtered to
        // status IN (COMPLETED, PLAYING, BACKLOG, DUSTY) so DROPPED + WISHLIST never feed this.
        java.util.LinkedHashMap<String, Double> genreScores = new java.util.LinkedHashMap<>();
        for (UserGameDTO g : ratedGames) {
            if (g.getGenres() == null || g.getGenres().isEmpty()) continue;
            java.util.List<String> validGenres = g.getGenres().stream()
                    .filter(genre -> genre != null && !genre.isBlank())
                    .toList();
            if (validGenres.isEmpty()) continue;
            double weight = 1.0 / validGenres.size();
            for (String genre : validGenres) {
                genreScores.merge(genre, weight, Double::sum);
            }
        }
        java.util.List<String> genrePriority = genreScores.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Double>comparingByValue().reversed())
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toList());

        List<com.thegamecellar.recommendationservice.model.dto.RecommendationRow> rows = new ArrayList<>();
        Set<Integer> seenAcrossRows = new HashSet<>();
        Set<String> usedGenres = new HashSet<>();

        for (String genre : genrePriority) {
            if (rows.size() >= GROUPED_TARGET_ROWS) break;
            List<RecommendationDTO> rowGames = buildGenreRow(genre, ownedGameIds, seenAcrossRows, recentlyShown, userPlatforms, platformProfile, bearerToken, tier);
            if (rowGames.isEmpty()) continue;
            rowGames.forEach(g -> seenAcrossRows.add(g.getIgdbId()));
            usedGenres.add(genre);
            rows.add(com.thegamecellar.recommendationservice.model.dto.RecommendationRow.builder()
                    .label(genre)
                    .genre(genre)
                    .fallback(false)
                    .games(rowGames)
                    .build());
        }

        // Discovery row: random pick from genres ranked beyond the top 8 so long-tail genres
        // in the user's library still surface occasionally. Different genre per refresh.
        java.util.List<String> longTail = genrePriority.stream()
                .filter(g -> !usedGenres.contains(g))
                .collect(Collectors.toList());
        if (!longTail.isEmpty()) {
            String pick = longTail.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(longTail.size()));
            List<RecommendationDTO> discoveryGames = buildGenreRow(pick, ownedGameIds, seenAcrossRows, recentlyShown, userPlatforms, platformProfile, bearerToken, tier);
            if (!discoveryGames.isEmpty()) {
                discoveryGames.forEach(g -> seenAcrossRows.add(g.getIgdbId()));
                rows.add(com.thegamecellar.recommendationservice.model.dto.RecommendationRow.builder()
                        .label(pick)
                        .genre(pick)
                        .fallback(false)
                        .games(discoveryGames)
                        .build());
            }
        }

        // Pad remaining slots with popular fallback when user genres are exhausted before 8.
        while (rows.size() < GROUPED_TARGET_ROWS) {
            Set<Integer> exclude = new HashSet<>(ownedGameIds);
            exclude.addAll(seenAcrossRows);
            List<RecommendationDTO> fallback = getTier3(exclude, recentlyShown, userPlatforms, GROUPED_PER_ROW, bearerToken);
            if (fallback.isEmpty()) break;
            fallback.forEach(g -> seenAcrossRows.add(g.getIgdbId()));
            rows.add(com.thegamecellar.recommendationservice.model.dto.RecommendationRow.builder()
                    .label(userPlatforms.isEmpty() ? "Popular games" : "Popular on your platforms")
                    .fallback(true)
                    .games(fallback)
                    .build());
            // One popular row is enough; duplicate popular rows would just repeat content.
            break;
        }

        String emptyMessage = null;
        if (genrePriority.size() < GROUPED_TARGET_ROWS) {
            emptyMessage = "Rate more games in your library for richer recommendations across more genres.";
        }

        return com.thegamecellar.recommendationservice.model.dto.GroupedRecommendationsResponse.builder()
                .rows(rows)
                .tier(tier == RecommendationTier.ONE ? 1 : 2)
                .emptyMessage(emptyMessage)
                .build();
    }

    private List<RecommendationDTO> buildGenreRow(String genre,
                                                   Set<Integer> ownedGameIds,
                                                   Set<Integer> seenAcrossRows,
                                                   Set<Integer> recentlyShown,
                                                   Set<String> userPlatforms,
                                                   Map<String, Double> platformProfile,
                                                   String bearerToken,
                                                   RecommendationTier tier) {
        // Oversample so cross-row dedupe + recency filtering still leaves a full row of fresh
        // games. Game Service runs ORDER BY RANDOM() over its quality-filtered subset so each
        // call gives a different sample.
        List<GameDTO> raw = gameServiceClient.randomQualityByGenre(
                genre, MIN_EFFECTIVE_RATING, MIN_RATING_COUNT, GROUPED_OVERSAMPLE, bearerToken);
        List<GameDTO> filtered = raw.stream()
                .filter(Objects::nonNull)
                .filter(g -> g.getIgdbId() != null)
                .filter(g -> !ownedGameIds.contains(g.getIgdbId()))
                .filter(g -> !seenAcrossRows.contains(g.getIgdbId()))
                .filter(g -> !recentlyShown.contains(g.getIgdbId()))
                .filter(g -> matchesAnyPlatform(g, userPlatforms))
                .collect(Collectors.toList());

        // Platform-boost ordering within the row. Genre is fixed so genre/theme/tag scoring
        // wouldn't differentiate much within a single-genre row; platform alignment is the
        // useful within-row signal. Materialise jittered scores in a map first; calling jitter
        // inside a Comparator violates the symmetric/transitive sort contract.
        if (!platformProfile.isEmpty() && !filtered.isEmpty()) {
            Map<Integer, Double> rowScores = new HashMap<>(filtered.size() * 2);
            for (GameDTO g : filtered) {
                double score = SimilarityScorer.platformBoost(g, platformProfile)
                        + ThreadLocalRandom.current().nextDouble() * SCORE_JITTER;
                rowScores.put(g.getIgdbId(), score);
            }
            filtered.sort(Comparator.comparingDouble((GameDTO g) -> rowScores.get(g.getIgdbId())).reversed());
        }

        return filtered.stream()
                .limit(GROUPED_PER_ROW)
                .map(g -> toDTO(g, "From your " + genre + " ratings", tier == RecommendationTier.ONE ? 1 : 2))
                .collect(Collectors.toList());
    }

    public List<RecommendationDTO> getPersonalized(String bearerToken, int limit, Set<Integer> recentlyShownIds) {
        List<UserGameDTO> allGames = Objects.requireNonNullElseGet(libraryServiceClient.getGames(bearerToken), List::of);
        List<UserGameDTO> ratedGames = allGames.stream()
                .filter(g -> g.getRating() != null)
                .filter(g -> isEligibleStatus(g.getStatus()))
                .toList();

        Set<Integer> ownedGameIds = allGames.stream()
                .map(UserGameDTO::getIgdbGameId)
                .collect(Collectors.toSet());

        // Caller-supplied "recently shown in this session" set used as a soft score penalty.
        // recently-shown games drop out of top MMR picks but can resurface if the pool runs
        // dry, so the system degrades gracefully without ever returning truly empty.
        Set<Integer> recentlyShown = recentlyShownIds == null ? Set.of() : recentlyShownIds;

        List<UserPlatformDTO> platformList = Objects.requireNonNullElseGet(libraryServiceClient.getPlatforms(bearerToken), List::of);
        Set<String> userPlatforms = platformList.stream()
                .map(UserPlatformDTO::getPlatformName)
                .collect(Collectors.toSet());

        // Declared preferences feed cold-start priors in the genre, tag and release-year
        // dimensions; floor in UserProfileBuilder keeps a permanent slice of each even after
        // rating evidence saturates.
        List<String> genrePreferences = Objects.requireNonNullElseGet(libraryServiceClient.getGenrePreferences(bearerToken), List::of);
        List<String> tagPreferences = Objects.requireNonNullElseGet(libraryServiceClient.getTagPreferences(bearerToken), List::of);
        List<String> releaseYearPreferences = Objects.requireNonNullElseGet(libraryServiceClient.getReleaseYearPreferences(bearerToken), List::of);

        RecommendationTier tier = TierSelector.select(ratedGames.size());

        return switch (tier) {
            case ONE -> getTier1(ratedGames, ownedGameIds, recentlyShown, userPlatforms, platformList, genrePreferences, tagPreferences, releaseYearPreferences, limit, bearerToken);
            case TWO -> getTier2(ratedGames, ownedGameIds, recentlyShown, userPlatforms, platformList, genrePreferences, tagPreferences, releaseYearPreferences, limit, bearerToken);
            case THREE -> getTier3(ownedGameIds, recentlyShown, userPlatforms, limit, bearerToken);
        };
    }

    private List<RecommendationDTO> getTier1(List<UserGameDTO> ratedGames,
                                              Set<Integer> ownedGameIds,
                                              Set<Integer> recentlyShownIds,
                                              Set<String> userPlatforms,
                                              List<UserPlatformDTO> platformList,
                                              List<String> genrePreferences,
                                              List<String> tagPreferences,
                                              List<String> releaseYearPreferences,
                                              int limit,
                                              String bearerToken) {
        UserProfile profile = UserProfileBuilder.buildMultiDim(ratedGames, platformList, genrePreferences, tagPreferences, releaseYearPreferences);

        // Weighted random sampling (Efraimidis-Spirakis A-Res): higher-rated genres appear more
        // often but all genres have a chance, preserving variety across requests. Bound at 8 to
        // limit fanout to Game Service.
        List<String> genresToSearch = UserProfileBuilder.sampleWeighted(profile.genres(), 8);
        List<GameDTO> candidates = new ArrayList<>();
        for (String genre : genresToSearch) {
            candidates.addAll(gameServiceClient.randomQualityByGenre(genre, MIN_EFFECTIVE_RATING, MIN_RATING_COUNT, 100, bearerToken));
        }

        // Augment with IGDB similar-games graph from top-rated owned titles. This brings in
        // titles that share fan-graphs with the user's favorites even if they don't share genres
        // (helps cold-start and surfaces niche neighbors a pure genre search would miss). These
        // candidates bypass the server-side /random-quality gate so we filter them client-side.
        fetchSimilarGraphCandidates(ratedGames, TIER1_RATED_FOR_GRAPH, bearerToken).stream()
                .filter(this::meetsQualityBar)
                .forEach(candidates::add);

        // Deduplicate, filter by platform, exclude owned, gate on quality, score and sort
        Set<Integer> seen = new HashSet<>();
        List<GameDTO> filtered = candidates.stream()
                .filter(Objects::nonNull)
                .filter(g -> g.getIgdbId() != null)
                .filter(g -> seen.add(g.getIgdbId()))
                .filter(g -> !ownedGameIds.contains(g.getIgdbId()))
                .filter(g -> matchesAnyPlatform(g, userPlatforms))
                .toList();

        // Fallback: if all genre searches returned nothing (cache sparse) or the quality
        // gate filtered everything out, serve popular games so the dashboard is never empty.
        if (filtered.isEmpty()) {
            log.warn("Tier 1 genre search returned no candidates, falling back to popular games");
            return getTier3(ownedGameIds, recentlyShownIds, userPlatforms, limit, bearerToken);
        }

        // Weighted-cosine multi-dim score then MMR re-rank for diversity. The pool
        // size is intentionally large (top-100 by relevance) so MMR has variety to draw from
        // when picking the diversified output slots. Jitter randomizes the MMR anchor per call
        // so identical candidate pools don't produce identical output orders. Recency penalty
        // pushes recently-shown games out of top picks until logout clears the client list.
        Map<Integer, Double> scoreMap = buildScoreMap(filtered, profile, recentlyShownIds);
        List<GameDTO> scored = filtered.stream()
                .sorted(Comparator.comparingDouble((GameDTO g) -> scoreMap.get(g.getIgdbId())).reversed())
                .toList();
        List<GameDTO> pool = scored.subList(0, Math.min(100, scored.size()));
        List<GameDTO> diversified = MMRReRanker.reRank(
                pool, profile, limit, MMRReRanker.DEFAULT_LAMBDA, SCORE_JITTER, SHOWN_PENALTY, recentlyShownIds);

        return diversified.stream()
                .map(g -> toDTO(g, "Based on your ratings", 1))
                .toList();
    }

    private List<RecommendationDTO> getTier2(List<UserGameDTO> ratedGames,
                                              Set<Integer> ownedGameIds,
                                              Set<Integer> recentlyShownIds,
                                              Set<String> userPlatforms,
                                              List<UserPlatformDTO> platformList,
                                              List<String> genrePreferences,
                                              List<String> tagPreferences,
                                              List<String> releaseYearPreferences,
                                              int limit,
                                              String bearerToken) {
        UserProfile profile = UserProfileBuilder.buildMultiDim(ratedGames, platformList, genrePreferences, tagPreferences, releaseYearPreferences);
        List<String> genresToSearch = UserProfileBuilder.sampleWeighted(profile.genres(), 5);

        List<GameDTO> candidates = new ArrayList<>();
        for (String genre : genresToSearch) {
            candidates.addAll(gameServiceClient.randomQualityByGenre(genre, MIN_EFFECTIVE_RATING, MIN_RATING_COUNT, 100, bearerToken));
        }

        // Augment with IGDB similar-games graph from top-rated owned titles. Quality-gate
        // these client-side since they bypass the server-side /random-quality filter.
        fetchSimilarGraphCandidates(ratedGames, TIER2_RATED_FOR_GRAPH, bearerToken).stream()
                .filter(this::meetsQualityBar)
                .forEach(candidates::add);

        Set<Integer> seen = new HashSet<>();
        List<GameDTO> filtered = candidates.stream()
                .filter(Objects::nonNull)
                .filter(g -> g.getIgdbId() != null)
                .filter(g -> seen.add(g.getIgdbId()))
                .filter(g -> !ownedGameIds.contains(g.getIgdbId()))
                .filter(g -> matchesAnyPlatform(g, userPlatforms))
                .collect(Collectors.toList());

        // Fallback: if genre search yields nothing (e.g. Game Service degraded) or the quality
        // gate filtered everything out, use popular games.
        if (filtered.isEmpty()) {
            log.warn("Tier 2 genre search returned no candidates, falling back to popular games");
            return getTier3(ownedGameIds, recentlyShownIds, userPlatforms, limit, bearerToken);
        }

        Map<Integer, Double> scoreMap = buildScoreMap(filtered, profile, recentlyShownIds);
        List<GameDTO> scored = filtered.stream()
                .sorted(Comparator.comparingDouble((GameDTO g) -> scoreMap.get(g.getIgdbId())).reversed())
                .toList();
        List<GameDTO> pool = scored.subList(0, Math.min(60, scored.size()));
        List<GameDTO> diversified = MMRReRanker.reRank(
                pool, profile, limit, MMRReRanker.DEFAULT_LAMBDA, SCORE_JITTER, SHOWN_PENALTY, recentlyShownIds);

        return diversified.stream()
                .map(g -> toDTO(g, "Popular in your genres", 2))
                .toList();
    }

    private List<RecommendationDTO> getTier3(Set<Integer> ownedGameIds,
                                              Set<Integer> recentlyShownIds,
                                              Set<String> userPlatforms,
                                              int limit,
                                              String bearerToken) {
        List<GameDTO> popular = new ArrayList<>();

        if (userPlatforms.isEmpty()) {
            // No platforms set, fall back to globally popular
            popular.addAll(gameServiceClient.getPopularGames(null, bearerToken));
        } else {
            for (String platform : userPlatforms) {
                popular.addAll(gameServiceClient.getPopularGames(platform, bearerToken));
            }
        }

        Set<Integer> seen = new HashSet<>();
        List<GameDTO> filtered = popular.stream()
                .filter(Objects::nonNull)
                .filter(g -> g.getIgdbId() != null)
                .filter(g -> seen.add(g.getIgdbId()))
                .filter(g -> !ownedGameIds.contains(g.getIgdbId()))
                .collect(Collectors.toList());

        // Split fresh vs recently shown so refreshes surface unseen popular games first;
        // recently-shown only fill leftover slots if the fresh pool runs short.
        List<GameDTO> fresh = new ArrayList<>();
        List<GameDTO> alreadyShown = new ArrayList<>();
        for (GameDTO g : filtered) {
            if (recentlyShownIds.contains(g.getIgdbId())) alreadyShown.add(g);
            else fresh.add(g);
        }
        Collections.shuffle(fresh);
        Collections.shuffle(alreadyShown);
        List<GameDTO> ordered = new ArrayList<>(fresh);
        ordered.addAll(alreadyShown);

        String reason = userPlatforms.isEmpty() ? "Popular games" : "Popular on your platforms";
        return ordered.subList(0, Math.min(limit, ordered.size())).stream()
                .map(g -> toDTO(g, reason, 3))
                .toList();
    }

    /**
     * Pulls candidates from IGDB's similar_games graph for the user's top-N rated titles.
     * Returns up to MAX_SIMILAR_FETCHES games, deduplicated. Skips rated titles whose cached
     * GameDTO has no similar_games payload yet (worker still backfilling).
     */
    private List<GameDTO> fetchSimilarGraphCandidates(List<UserGameDTO> ratedGames,
                                                       int topN,
                                                       String bearerToken) {
        if (ratedGames.isEmpty()) return List.of();

        List<UserGameDTO> topRated = ratedGames.stream()
                .filter(g -> g.getRating() != null)
                .sorted(Comparator.comparingInt(UserGameDTO::getRating).reversed())
                .limit(topN)
                .toList();

        Set<Integer> ratedIds = topRated.stream()
                .map(UserGameDTO::getIgdbGameId)
                .collect(Collectors.toSet());

        List<CompletableFuture<GameDTO>> sourceFutures = topRated.stream()
                .map(rated -> CompletableFuture.supplyAsync(
                        () -> gameServiceClient.getGameById(rated.getIgdbGameId(), bearerToken)))
                .toList();
        CompletableFuture.allOf(sourceFutures.toArray(new CompletableFuture[0])).join();

        Set<Integer> similarIds = new LinkedHashSet<>();
        for (CompletableFuture<GameDTO> f : sourceFutures) {
            if (similarIds.size() >= MAX_SIMILAR_FETCHES) break;
            GameDTO source = f.join();
            if (source == null || source.getSimilarGameIds() == null) continue;
            for (Integer id : source.getSimilarGameIds()) {
                if (id == null || ratedIds.contains(id)) continue;
                similarIds.add(id);
                if (similarIds.size() >= MAX_SIMILAR_FETCHES) break;
            }
        }

        if (similarIds.isEmpty()) return List.of();

        List<CompletableFuture<GameDTO>> similarFutures = similarIds.stream()
                .map(id -> CompletableFuture.supplyAsync(
                        () -> gameServiceClient.getGameById(id, bearerToken)))
                .toList();
        CompletableFuture.allOf(similarFutures.toArray(new CompletableFuture[0])).join();

        return similarFutures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Materialize per-candidate jittered + recency-penalized score into a stable map keyed by
     * igdbId. Must be called once per request before sorting; calling jitter inside a
     * Comparator violates the symmetric/transitive sort contract because each lookup re-rolls
     * the random component.
     */
    private Map<Integer, Double> buildScoreMap(List<GameDTO> candidates,
                                                UserProfile profile,
                                                Set<Integer> recentlyShownIds) {
        Map<Integer, Double> scores = new HashMap<>(candidates.size() * 2);
        for (GameDTO candidate : candidates) {
            double score = SimilarityScorer.scoreMultiDim(candidate, profile)
                    + SimilarityScorer.EPSILON * SimilarityScorer.platformBoost(candidate, profile.platforms())
                    + SimilarityScorer.RELEASE_YEAR_EPSILON * SimilarityScorer.releaseYearBoost(candidate, profile.declaredReleaseYears())
                    + ThreadLocalRandom.current().nextDouble() * SCORE_JITTER;
            if (recentlyShownIds.contains(candidate.getIgdbId())) {
                score -= SHOWN_PENALTY;
            }
            scores.put(candidate.getIgdbId(), score);
        }
        return scores;
    }

    /**
     * Quality gate kept for similar-graph candidates only. The genre-source path is now
     * pre-filtered by Game Service /random-quality at SQL level. Similar-graph entries come
     * from {@code getGameById} on IGDB-supplied neighbour ids, so they bypass the server gate
     * and need a client-side check before they enter the scoring pool.
     */
    private boolean meetsQualityBar(GameDTO game) {
        if (game == null) return false;
        Integer voteCount = game.getTotalRatingCount();
        if (voteCount == null || voteCount < MIN_RATING_COUNT) return false;
        java.math.BigDecimal effective = game.getTotalRating() != null ? game.getTotalRating() : game.getRating();
        if (effective == null) return false;
        return effective.compareTo(MIN_EFFECTIVE_RATING) >= 0;
    }

    /**
     * Status filter for the rated-set that feeds profile + genre-priority. DROPPED games signal
     * "user stopped, not their taste" and WISHLIST games haven't been played yet, so neither
     * should shape recommendations even when rated. Null treated as eligible so legacy rows
     * without an explicit status don't silently drop out of the profile.
     */
    private static boolean isEligibleStatus(String status) {
        if (status == null) return true;
        return switch (status) {
            case "COMPLETED", "PLAYING", "BACKLOG", "DUSTY" -> true;
            default -> false;
        };
    }

    private boolean matchesAnyPlatform(GameDTO game, Set<String> userPlatforms) {
        if (userPlatforms.isEmpty()) return true;
        if (game.getPlatforms() == null) return false;
        return game.getPlatforms().stream().anyMatch(userPlatforms::contains);
    }

    private RecommendationDTO toDTO(GameDTO game, String reason, int tier) {
        return RecommendationDTO.builder()
                .igdbId(game.getIgdbId())
                .name(game.getName())
                .rating(game.getRating())
                .backgroundImage(game.getBackgroundImage())
                .genres(game.getGenres())
                .platforms(game.getPlatforms())
                .reason(reason)
                .tier(tier)
                .build();
    }
}
