package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.algorithm.RecommendationTier;
import com.thegamecellar.recommendationservice.algorithm.SimilarityScorer;
import com.thegamecellar.recommendationservice.algorithm.TierSelector;
import com.thegamecellar.recommendationservice.algorithm.UserProfile;
import com.thegamecellar.recommendationservice.algorithm.UserProfileBuilder;
import com.thegamecellar.recommendationservice.client.InternalGameClient;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

// Worker-only computer. Produces raw-scored candidates with denormalized game data so the
// request path can build RecommendationDTO without a game-service hop. Fetch + raw-score
// only. Jitter, shown-penalty, and MMR run at read time on top of these rows. No JWT-aware
// client here - the worker has no user JWT.
@Slf4j
@Service
public class RecommendationComputer {

    public static final int DEFAULT_POOL_SIZE = 2500;

    private static final int TIER1_GENRES_TO_SAMPLE = 12;
    private static final int TIER2_GENRES_TO_SAMPLE = 7;
    private static final int PER_GENRE_FETCH = 200;
    // Two passes per genre. randomQualityByGenre uses ORDER BY RANDOM() server-side so the
    // second call returns a mostly-different sample. Doubles raw count without raising per-call
    // limit (game-service @Max(200) cap). Roughly halves dedupe loss vs single pass.
    private static final int FETCH_PASSES = 2;
    private static final int TIER1_RATED_FOR_GRAPH = 8;
    private static final int TIER2_RATED_FOR_GRAPH = 5;
    private static final int MAX_SIMILAR_FETCHES = 30;
    private static final BigDecimal MIN_EFFECTIVE_RATING = new BigDecimal("7.0");
    private static final int MIN_RATING_COUNT = 10;

    // user_candidate_pool.base_score is NUMERIC(5,4). Cap at 9.9999 so overflow cannot crash inserts.
    private static final BigDecimal SCORE_CAP = new BigDecimal("9.9999");

    public record PoolCandidate(
            Integer igdbId,
            BigDecimal baseScore,
            short tier,
            String name,
            String backgroundImage,
            BigDecimal rating,
            List<String> genres,
            List<String> platforms
    ) {}

    public record Result(UserProfile profile, List<PoolCandidate> candidates, RecommendationTier tier) {}

    // Per-genre top-up. Single-genre fetch with hold-set exclusion (worker parks the displaced
    // bucket in pool_holding so this fetch skips them; the next 1-2 ticks release them back).
    // Score with same profile shape as full compute so base_score sorts comparably across
    // top-up rounds. Returns only candidates for the requested genre.
    public List<PoolCandidate> topUpGenre(String genre,
                                          Set<Integer> excludeIgdbIds,
                                          List<UserGameDTO> allGames,
                                          List<UserPlatformDTO> platformList,
                                          List<String> genrePrefs,
                                          List<String> tagPrefs,
                                          List<String> yearPrefs,
                                          InternalGameClient gameClient,
                                          int topUpSize) {
        List<UserGameDTO> ratedGames = allGames.stream()
                .filter(g -> g.getRating() != null)
                .filter(g -> isEligibleStatus(g.getStatus()))
                .toList();

        Set<Integer> ownedGameIds = allGames.stream()
                .map(UserGameDTO::getIgdbGameId)
                .collect(Collectors.toSet());

        Set<String> userPlatforms = platformList.stream()
                .map(UserPlatformDTO::getPlatformName)
                .collect(Collectors.toSet());

        UserProfile profile = UserProfileBuilder.buildMultiDim(
                ratedGames, platformList, genrePrefs, tagPrefs, yearPrefs);

        // Two passes for the single genre to widen the raw set under randomQualityByGenre's
        // ORDER BY RANDOM(). Same FETCH_PASSES knob as full compute keeps yield comparable.
        List<GameDTO> raw = new ArrayList<>();
        Set<Integer> seen = new HashSet<>(excludeIgdbIds);
        for (int pass = 0; pass < FETCH_PASSES; pass++) {
            List<GameDTO> fetched = gameClient.randomQualityByGenre(
                    genre, MIN_EFFECTIVE_RATING, MIN_RATING_COUNT, PER_GENRE_FETCH);
            for (GameDTO g : fetched) {
                if (g != null && g.getIgdbId() != null && seen.add(g.getIgdbId())) {
                    raw.add(g);
                }
            }
        }

        List<GameDTO> filtered = dedupeAndFilter(raw, ownedGameIds, userPlatforms);
        if (filtered.isEmpty()) return List.of();
        return scoreAndCap(filtered, profile, (short) 1, topUpSize);
    }

    public Result computePool(List<UserGameDTO> allGames,
                              List<UserPlatformDTO> platformList,
                              List<String> genrePrefs,
                              List<String> tagPrefs,
                              List<String> yearPrefs,
                              InternalGameClient gameClient,
                              int poolSize) {
        List<UserGameDTO> ratedGames = allGames.stream()
                .filter(g -> g.getRating() != null)
                .filter(g -> isEligibleStatus(g.getStatus()))
                .toList();

        Set<Integer> ownedGameIds = allGames.stream()
                .map(UserGameDTO::getIgdbGameId)
                .collect(Collectors.toSet());

        Set<String> userPlatforms = platformList.stream()
                .map(UserPlatformDTO::getPlatformName)
                .collect(Collectors.toSet());

        UserProfile profile = UserProfileBuilder.buildMultiDim(
                ratedGames, platformList, genrePrefs, tagPrefs, yearPrefs);

        RecommendationTier tier = TierSelector.select(ratedGames.size());

        List<PoolCandidate> candidates = switch (tier) {
            case ONE -> tier1(ratedGames, ownedGameIds, userPlatforms, profile, gameClient, poolSize);
            case TWO -> tier2(ratedGames, ownedGameIds, userPlatforms, profile, gameClient, poolSize);
            case THREE -> tier3(ownedGameIds, userPlatforms, gameClient, poolSize);
        };

        return new Result(profile, candidates, tier);
    }

    private List<PoolCandidate> tier1(List<UserGameDTO> ratedGames,
                                      Set<Integer> ownedGameIds,
                                      Set<String> userPlatforms,
                                      UserProfile profile,
                                      InternalGameClient gameClient,
                                      int poolSize) {
        List<String> genresToSearch = UserProfileBuilder.sampleWeighted(profile.genres(), TIER1_GENRES_TO_SAMPLE);

        List<GameDTO> raw = fetchGenreCandidatesMultiPass(gameClient, genresToSearch);
        fetchSimilarGraphCandidates(ratedGames, TIER1_RATED_FOR_GRAPH, gameClient).stream()
                .filter(this::meetsQualityBar)
                .forEach(raw::add);

        List<GameDTO> filtered = dedupeAndFilter(raw, ownedGameIds, userPlatforms);
        if (filtered.isEmpty()) {
            log.warn("Tier 1 produced no candidates, falling back to Tier 3");
            return tier3(ownedGameIds, userPlatforms, gameClient, poolSize);
        }
        return scoreAndCap(filtered, profile, (short) 1, poolSize);
    }

    private List<PoolCandidate> tier2(List<UserGameDTO> ratedGames,
                                      Set<Integer> ownedGameIds,
                                      Set<String> userPlatforms,
                                      UserProfile profile,
                                      InternalGameClient gameClient,
                                      int poolSize) {
        List<String> genresToSearch = UserProfileBuilder.sampleWeighted(profile.genres(), TIER2_GENRES_TO_SAMPLE);

        List<GameDTO> raw = fetchGenreCandidatesMultiPass(gameClient, genresToSearch);
        fetchSimilarGraphCandidates(ratedGames, TIER2_RATED_FOR_GRAPH, gameClient).stream()
                .filter(this::meetsQualityBar)
                .forEach(raw::add);

        List<GameDTO> filtered = dedupeAndFilter(raw, ownedGameIds, userPlatforms);
        if (filtered.isEmpty()) {
            log.warn("Tier 2 produced no candidates, falling back to Tier 3");
            return tier3(ownedGameIds, userPlatforms, gameClient, poolSize);
        }
        return scoreAndCap(filtered, profile, (short) 2, poolSize);
    }

    private List<PoolCandidate> tier3(Set<Integer> ownedGameIds,
                                      Set<String> userPlatforms,
                                      InternalGameClient gameClient,
                                      int poolSize) {
        List<GameDTO> popular = new ArrayList<>();
        if (userPlatforms.isEmpty()) {
            popular.addAll(gameClient.getPopularGames(null));
        } else {
            for (String platform : userPlatforms) {
                popular.addAll(gameClient.getPopularGames(platform));
            }
        }

        Set<Integer> seen = new HashSet<>();
        List<GameDTO> filtered = popular.stream()
                .filter(Objects::nonNull)
                .filter(g -> g.getIgdbId() != null)
                .filter(g -> seen.add(g.getIgdbId()))
                .filter(g -> !ownedGameIds.contains(g.getIgdbId()))
                .toList();

        List<PoolCandidate> out = new ArrayList<>(Math.min(filtered.size(), poolSize));
        int idx = 0;
        for (GameDTO g : filtered) {
            if (out.size() >= poolSize) break;
            double score = 1.0 - ((double) idx / Math.max(1, filtered.size()));
            out.add(toPoolCandidate(g, clamp(score), (short) 3));
            idx++;
        }
        return out;
    }

    private List<GameDTO> fetchGenreCandidatesMultiPass(InternalGameClient gameClient, List<String> genres) {
        List<GameDTO> raw = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int pass = 0; pass < FETCH_PASSES; pass++) {
            for (String genre : genres) {
                List<GameDTO> fetched = gameClient.randomQualityByGenre(
                        genre, MIN_EFFECTIVE_RATING, MIN_RATING_COUNT, PER_GENRE_FETCH);
                for (GameDTO g : fetched) {
                    if (g != null && g.getIgdbId() != null && seen.add(g.getIgdbId())) {
                        raw.add(g);
                    }
                }
            }
        }
        return raw;
    }

    private List<GameDTO> dedupeAndFilter(List<GameDTO> raw,
                                          Set<Integer> ownedGameIds,
                                          Set<String> userPlatforms) {
        Set<Integer> seen = new HashSet<>();
        return raw.stream()
                .filter(Objects::nonNull)
                .filter(g -> g.getIgdbId() != null)
                .filter(g -> seen.add(g.getIgdbId()))
                .filter(g -> !ownedGameIds.contains(g.getIgdbId()))
                .filter(g -> matchesAnyPlatform(g, userPlatforms))
                .toList();
    }

    private List<PoolCandidate> scoreAndCap(List<GameDTO> filtered, UserProfile profile, short tier, int poolSize) {
        record Scored(GameDTO game, double score) {}
        List<Scored> scored = new ArrayList<>(filtered.size());
        for (GameDTO g : filtered) {
            double s = SimilarityScorer.scoreMultiDim(g, profile)
                    + SimilarityScorer.EPSILON * SimilarityScorer.platformBoost(g, profile.platforms())
                    + SimilarityScorer.RELEASE_YEAR_EPSILON * SimilarityScorer.releaseYearBoost(g, profile.declaredReleaseYears());
            scored.add(new Scored(g, s));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());

        List<PoolCandidate> out = new ArrayList<>(Math.min(scored.size(), poolSize));
        for (Scored s : scored) {
            if (out.size() >= poolSize) break;
            out.add(toPoolCandidate(s.game, clamp(s.score), tier));
        }
        return out;
    }

    private PoolCandidate toPoolCandidate(GameDTO g, BigDecimal score, short tier) {
        List<String> genres = g.getGenres() == null ? List.of() : List.copyOf(g.getGenres());
        List<String> platforms = g.getPlatforms() == null ? List.of() : List.copyOf(g.getPlatforms());
        BigDecimal rating = g.getTotalRating() != null ? g.getTotalRating() : g.getRating();
        String name = g.getName() != null ? g.getName() : "";
        return new PoolCandidate(
                g.getIgdbId(),
                score,
                tier,
                name,
                g.getBackgroundImage(),
                rating,
                genres,
                platforms
        );
    }

    private List<GameDTO> fetchSimilarGraphCandidates(List<UserGameDTO> ratedGames,
                                                     int topN,
                                                     InternalGameClient gameClient) {
        if (ratedGames.isEmpty()) return List.of();

        List<UserGameDTO> topRated = ratedGames.stream()
                .filter(g -> g.getRating() != null)
                .sorted(Comparator.comparingInt(UserGameDTO::getRating).reversed())
                .limit(topN)
                .toList();
        Set<Integer> ratedIds = topRated.stream()
                .map(UserGameDTO::getIgdbGameId)
                .collect(Collectors.toSet());

        Set<Integer> similarIds = new LinkedHashSet<>();
        for (UserGameDTO rated : topRated) {
            if (similarIds.size() >= MAX_SIMILAR_FETCHES) break;
            GameDTO src = gameClient.getGameById(rated.getIgdbGameId());
            if (src == null || src.getSimilarGameIds() == null) continue;
            for (Integer id : src.getSimilarGameIds()) {
                if (id == null || ratedIds.contains(id)) continue;
                similarIds.add(id);
                if (similarIds.size() >= MAX_SIMILAR_FETCHES) break;
            }
        }
        if (similarIds.isEmpty()) return List.of();

        List<GameDTO> similars = new ArrayList<>(similarIds.size());
        for (Integer id : similarIds) {
            GameDTO g = gameClient.getGameById(id);
            if (g != null) similars.add(g);
        }
        return similars;
    }

    private static BigDecimal clamp(double raw) {
        BigDecimal bd = BigDecimal.valueOf(Math.max(0.0, raw)).setScale(4, java.math.RoundingMode.HALF_UP);
        return bd.compareTo(SCORE_CAP) > 0 ? SCORE_CAP : bd;
    }

    private boolean meetsQualityBar(GameDTO game) {
        if (game == null) return false;
        Integer voteCount = game.getTotalRatingCount();
        if (voteCount == null || voteCount < MIN_RATING_COUNT) return false;
        BigDecimal effective = game.getTotalRating() != null ? game.getTotalRating() : game.getRating();
        if (effective == null) return false;
        return effective.compareTo(MIN_EFFECTIVE_RATING) >= 0;
    }

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
}
