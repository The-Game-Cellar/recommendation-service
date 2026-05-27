package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.GameServiceClient;
import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.model.dto.GroupedRecommendationsResponse;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.RecommendationRow;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import com.thegamecellar.recommendationservice.repository.UserCandidatePoolRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

// Request path. Reads denormalized candidate pool from rec_db, applies jitter +
// shown-penalty + MMR in-memory so two consecutive refreshes do not return the same
// ordering. No game-service or library-service hop in the warm path - pool rows carry the
// data inline. Cold-start (empty pool) enqueues a worker job and serves a tier-3 popular
// fallback in real-time. Pool refill is triggered when recentlyShownIds intersect >= 50%
// of the pool size so the user never observes "out of games" gaps.
@Slf4j
@Service
public class RecommendationService {

    private static final double SHOWN_PENALTY = 0.40;
    private static final double MIN_WEIGHT = 0.0001;
    private static final int SAMPLE_POOL = 200;
    private static final double MMR_LAMBDA = 0.85;

    public static final int GROUPED_TARGET_ROWS = 8;
    public static final int GROUPED_PER_ROW = 15;

    private final UserCandidatePoolRepository poolRepository;
    private final UserProfileCache profileCache;
    private final ComputeEnqueuer computeEnqueuer;
    private final GameServiceClient gameServiceClient;
    private final LibraryServiceClient libraryServiceClient;

    private final int poolSize;
    private final int refillThresholdPct;
    private final long staleTtlHours;

    public RecommendationService(UserCandidatePoolRepository poolRepository,
                                 UserProfileCache profileCache,
                                 ComputeEnqueuer computeEnqueuer,
                                 GameServiceClient gameServiceClient,
                                 LibraryServiceClient libraryServiceClient,
                                 @Value("${recommendation.pool.size:2500}") int poolSize,
                                 @Value("${recommendation.pool.refill-threshold-percent:50}") int refillThresholdPct,
                                 @Value("${recommendation.stale-ttl-hours:24}") long staleTtlHours) {
        this.poolRepository = poolRepository;
        this.profileCache = profileCache;
        this.computeEnqueuer = computeEnqueuer;
        this.gameServiceClient = gameServiceClient;
        this.libraryServiceClient = libraryServiceClient;
        this.poolSize = poolSize;
        this.refillThresholdPct = refillThresholdPct;
        this.staleTtlHours = staleTtlHours;
    }

    public List<RecommendationDTO> getPersonalized(String userId,
                                                   String bearerToken,
                                                   int limit,
                                                   Set<Integer> recentlyShownIds) {
        List<UserCandidatePool> pool = poolRepository.findByUserId(userId);
        Set<Integer> shown = recentlyShownIds == null ? Set.of() : recentlyShownIds;

        if (pool.isEmpty()) {
            enqueue(userId);
            return tier3FallbackRealtime(bearerToken, limit);
        }

        if (isStale(pool)) {
            enqueue(userId);
        }

        List<Scored> sample = weightedSample(pool, shown, SAMPLE_POOL);
        sample.sort(Comparator.<Scored>comparingInt(s -> s.row.getTier())
                .thenComparing((a, b) -> Double.compare(b.key, a.key)));
        List<Scored> diversified = diversify(sample, limit);

        triggerRefillIfDepleted(userId, pool, shown);

        boolean hasPlatforms = !diversified.isEmpty()
                && diversified.get(0).row.getPlatforms() != null
                && !diversified.get(0).row.getPlatforms().isEmpty();
        return diversified.stream()
                .map(s -> toDTO(toGameDTO(s.row),
                        tierReason(s.row.getTier(), hasPlatforms),
                        s.row.getTier()))
                .toList();
    }

    public GroupedRecommendationsResponse getPersonalizedGrouped(String userId,
                                                                 String bearerToken,
                                                                 Set<Integer> recentlyShownIds) {
        List<UserCandidatePool> pool = poolRepository.findByUserId(userId);
        Set<Integer> shown = recentlyShownIds == null ? Set.of() : recentlyShownIds;

        if (pool.isEmpty()) {
            enqueue(userId);
            return tier3FallbackGrouped(bearerToken);
        }

        if (isStale(pool)) {
            enqueue(userId);
        }

        boolean anyTier12 = pool.stream().anyMatch(r -> r.getTier() != null && r.getTier() <= 2);
        int responseTier = anyTier12 ? 1 : 3;

        if (!anyTier12) {
            return GroupedRecommendationsResponse.builder()
                    .rows(List.of(RecommendationRow.builder()
                            .label("Popular on your platforms")
                            .fallback(true)
                            .games(tier3FallbackRealtime(bearerToken, GROUPED_PER_ROW))
                            .build()))
                    .tier(3)
                    .emptyMessage("Rate games in your library to unlock personalized recommendations.")
                    .build();
        }

        // Row labels come from user library, not pool-row primary genre. A game with genres
        // ["Adventure", "Visual Novel"] would otherwise land under "Visual Novel" even when the
        // user only has Adventure in their lib. Pull the user's lib-genre set from snapshot,
        // bucket each pool row under whichever of its genres the user has the most of.
        UserProfileSnapshot snapshot = profileCache.findByUserId(userId).orElse(null);
        Map<String, Double> libraryGenreCounts = snapshot != null && snapshot.getLibraryGenreCounts() != null
                ? snapshot.getLibraryGenreCounts()
                : Map.of();
        Map<String, Double> userGenres = libraryGenreCounts.isEmpty()
                ? (snapshot != null && snapshot.getGenreWeights() != null ? snapshot.getGenreWeights() : Map.of())
                : libraryGenreCounts;

        // Bucket each row into EVERY user-genre it carries. Cross-row dedupe (seenAcrossRows
        // inside buildGenreRow) ensures a game appears in only one row. If we bucketed by
        // best-match only, narrow rows (Hack and slash 16) would lose every game that is also
        // tagged broad (Action 75) because Action wins the "best" pick.
        Map<String, List<Scored>> byGenre = new LinkedHashMap<>();
        List<Scored> keyedPool = scorePool(pool, shown);
        for (Scored s : keyedPool) {
            List<String> rowGenres = s.row.getGenres();
            if (rowGenres == null || rowGenres.isEmpty()) continue;
            for (String g : rowGenres) {
                if (g == null || g.isBlank()) continue;
                if (!userGenres.containsKey(g)) continue;
                byGenre.computeIfAbsent(g, k -> new ArrayList<>()).add(s);
            }
        }

        // Display order = user-count DESC (matches /profile/statistics). Picking order = ASC
        // so narrow rows claim their tagged games first via seenAcrossRows before broad rows
        // walk the same multi-bucket games.
        List<String> displayOrder = byGenre.keySet().stream()
                .sorted((a, b) -> {
                    double ca = userGenres.getOrDefault(a, (double) byGenre.get(a).size());
                    double cb = userGenres.getOrDefault(b, (double) byGenre.get(b).size());
                    return Double.compare(cb, ca);
                })
                .collect(Collectors.toList());
        List<String> pickOrder = byGenre.keySet().stream()
                .sorted((a, b) -> {
                    double ca = userGenres.getOrDefault(a, (double) byGenre.get(a).size());
                    double cb = userGenres.getOrDefault(b, (double) byGenre.get(b).size());
                    return Double.compare(ca, cb);
                })
                .collect(Collectors.toList());
        // Use displayOrder for the long-tail / variable name compatibility below.
        List<String> orderedGenres = displayOrder;

        Set<Integer> seenAcrossRows = new HashSet<>();
        Map<Integer, Integer> tierById = pool.stream()
                .collect(Collectors.toMap(UserCandidatePool::getIgdbId,
                        r -> r.getTier().intValue(), (a, b) -> a));

        // Restrict allocation to the top GROUPED_TARGET_ROWS user-genres so we don't burn
        // games on long-tail genres that won't render in the main row block. Within that
        // subset, narrow genres pick first.
        Set<String> visibleSet = new HashSet<>(displayOrder.subList(0, Math.min(GROUPED_TARGET_ROWS, displayOrder.size())));
        Map<String, RecommendationRow> rowsByGenre = new java.util.HashMap<>();
        for (String genre : pickOrder) {
            if (!visibleSet.contains(genre)) continue;
            RecommendationRow row = buildGenreRow(genre, byGenre.get(genre), seenAcrossRows, tierById);
            if (row != null) rowsByGenre.put(genre, row);
        }

        // Display rows in the user-count DESC order; drop genres that produced no row.
        List<RecommendationRow> rows = new ArrayList<>();
        Set<String> usedGenres = new HashSet<>();
        for (String genre : displayOrder) {
            if (rows.size() >= GROUPED_TARGET_ROWS) break;
            RecommendationRow row = rowsByGenre.get(genre);
            if (row == null) continue;
            usedGenres.add(genre);
            rows.add(row);
        }

        // Long-tail discovery row: random pick from genres beyond top GROUPED_TARGET_ROWS. Different
        // genre per refresh, surfaces user's underused genres. Same shape as a regular row.
        List<String> longTail = orderedGenres.stream()
                .filter(g -> !usedGenres.contains(g))
                .collect(Collectors.toList());
        if (!longTail.isEmpty()) {
            String pick = longTail.get(ThreadLocalRandom.current().nextInt(longTail.size()));
            RecommendationRow discoveryRow = buildGenreRow(pick, byGenre.get(pick), seenAcrossRows, tierById);
            if (discoveryRow != null) rows.add(discoveryRow);
        }

        if (rows.size() < GROUPED_TARGET_ROWS) {
            List<RecommendationDTO> popular = tier3FallbackRealtime(bearerToken, GROUPED_PER_ROW);
            if (!popular.isEmpty()) {
                rows.add(RecommendationRow.builder()
                        .label("Popular on your platforms")
                        .fallback(true)
                        .games(popular)
                        .build());
            }
        }

        triggerRefillIfDepleted(userId, pool, shown);

        String emptyMessage = orderedGenres.size() < GROUPED_TARGET_ROWS
                ? "Rate more games in your library for richer recommendations across more genres."
                : null;
        return GroupedRecommendationsResponse.builder()
                .rows(rows)
                .tier(responseTier)
                .emptyMessage(emptyMessage)
                .build();
    }

    // Per-row refresh on /recommendations. Returns a single genre bucket re-sampled with the
    // current jitter + shown-penalty, and enqueues a per-genre top-up so the next refresh has
    // strictly fresh ids. Frontend hits this when the user clicks the row's refresh button.
    public RecommendationRow getGenreRow(String userId, String genre, Set<Integer> recentlyShownIds) {
        List<UserCandidatePool> pool = poolRepository.findByUserId(userId);
        Set<Integer> shown = recentlyShownIds == null ? Set.of() : recentlyShownIds;

        if (pool.isEmpty()) {
            enqueue(userId);
            return null;
        }

        // Match the grouped path's allocation: a row belongs to every user-genre it carries.
        // Filter = row.genres.contains(genre), same rule the worker uses when evicting a bucket
        // for top-up. Earlier bestUserGenre-only check hid HnS rows (paired with Action) because
        // Action wins the best-match contest.
        List<Scored> bucket = scorePool(pool, shown).stream()
                .filter(s -> s.row.getGenres() != null && s.row.getGenres().contains(genre))
                .collect(Collectors.toList());

        Map<Integer, Integer> tierById = pool.stream()
                .collect(Collectors.toMap(UserCandidatePool::getIgdbId,
                        r -> r.getTier().intValue(), (a, b) -> a));

        // Each per-row refresh click signals freshness need. Enqueue a per-genre top-up so the
        // next refresh has strictly new ids (current bucket parks in pool_holding for ~60s).
        // Upsert is cheap when a job is already queued; logic in repository keeps full-replace
        // jobs precedence over per-genre ones.
        computeEnqueuer.enqueueGenre(userId, genre);

        return buildGenreRow(genre, bucket, new HashSet<>(), tierById);
    }

    // key = u^(1/weight). Higher key wins for top-K. Variation per request comes from u; weight
    // (base_score minus shown-penalty) biases the draw toward higher-quality candidates.
    private record Scored(UserCandidatePool row, double key) {}

    private RecommendationRow buildGenreRow(String genre,
                                            List<Scored> bucket,
                                            Set<Integer> seenAcrossRows,
                                            Map<Integer, Integer> tierById) {
        if (bucket == null || bucket.isEmpty()) return null;
        List<Scored> sorted = new ArrayList<>(bucket);
        sorted.sort((a, b) -> Double.compare(b.key, a.key));
        List<RecommendationDTO> rowGames = new ArrayList<>();
        for (Scored s : sorted) {
            if (rowGames.size() >= GROUPED_PER_ROW) break;
            Integer id = s.row.getIgdbId();
            if (seenAcrossRows.contains(id)) continue;
            seenAcrossRows.add(id);
            rowGames.add(toDTO(toGameDTO(s.row), "From your " + genre + " ratings",
                    tierById.getOrDefault(id, 1)));
        }
        if (rowGames.isEmpty()) return null;
        return RecommendationRow.builder()
                .label(genre)
                .genre(genre)
                .fallback(false)
                .games(rowGames)
                .build();
    }

    private List<Scored> diversify(List<Scored> sorted, int k) {
        if (sorted.isEmpty() || k <= 0) return List.of();
        int outputSize = Math.min(k, sorted.size());
        if (outputSize == sorted.size()) return new ArrayList<>(sorted);

        List<Scored> picked = new ArrayList<>(outputSize);
        List<Scored> remaining = new ArrayList<>(sorted);
        picked.add(remaining.remove(0));

        while (picked.size() < outputSize && !remaining.isEmpty()) {
            int bestIdx = 0;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < remaining.size(); i++) {
                Scored cand = remaining.get(i);
                double maxSim = 0.0;
                for (Scored p : picked) {
                    double sim = genreJaccard(cand.row.getGenres(), p.row.getGenres());
                    if (sim > maxSim) maxSim = sim;
                }
                double mmr = MMR_LAMBDA * cand.key - (1.0 - MMR_LAMBDA) * maxSim;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    bestIdx = i;
                }
            }
            picked.add(remaining.remove(bestIdx));
        }
        return picked;
    }

    private static double genreJaccard(List<String> a, List<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> sa = new HashSet<>(a);
        Set<String> sb = new HashSet<>(b);
        int intersection = 0;
        for (String s : sa) if (sb.contains(s)) intersection++;
        int union = sa.size() + sb.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    // Efraimidis-Spirakis A-Res weighted reservoir sample. Key = u^(1/w) over each row; sorting
    // by key DESC and taking top-K gives a weighted random sample without replacement. Different
    // u per request -> different top-K per refresh, while higher base_score raises pick prob.
    // Shown-penalty subtracts from weight so recently-shown ids are less likely but not excluded.
    private List<Scored> weightedSample(List<UserCandidatePool> pool, Set<Integer> shown, int k) {
        List<Scored> keyed = scorePool(pool, shown);
        keyed.sort((a, b) -> Double.compare(b.key, a.key));
        return new ArrayList<>(keyed.subList(0, Math.min(k, keyed.size())));
    }

    private List<Scored> scorePool(List<UserCandidatePool> pool, Set<Integer> shown) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Scored> out = new ArrayList<>(pool.size());
        for (UserCandidatePool row : pool) {
            double base = row.getBaseScore() == null ? 0.0 : row.getBaseScore().doubleValue();
            double weight = base;
            if (shown.contains(row.getIgdbId())) {
                weight -= SHOWN_PENALTY;
            }
            weight = Math.max(MIN_WEIGHT, weight);
            // u in (0,1) so log(u) is finite. Math.pow(u, 1/w) is equivalent but key = log(u)/w
            // is numerically more stable when w gets very small.
            double u = rnd.nextDouble();
            if (u <= 0.0) u = Double.MIN_NORMAL;
            double key = Math.log(u) / weight;
            out.add(new Scored(row, key));
        }
        return out;
    }

    private GameDTO toGameDTO(UserCandidatePool row) {
        GameDTO dto = new GameDTO();
        dto.setIgdbId(row.getIgdbId());
        dto.setName(row.getName());
        dto.setRating(row.getRating());
        dto.setTotalRating(row.getRating());
        dto.setBackgroundImage(row.getBackgroundImage());
        dto.setGenres(row.getGenres() == null ? Collections.emptyList() : row.getGenres());
        dto.setPlatforms(row.getPlatforms() == null ? Collections.emptyList() : row.getPlatforms());
        dto.setThemes(Collections.emptyList());
        dto.setTags(Collections.emptyList());
        return dto;
    }

    private static String primaryGenre(List<String> genres) {
        if (genres == null) return null;
        for (String g : genres) {
            if (g != null && !g.isBlank()) return g;
        }
        return null;
    }

    // Pick whichever of the row's genres the user has highest count for. Returns null when no
    // overlap so the row gets dropped (worker fetched outside user's profile, e.g. via a
    // similar-graph hop that landed in a genre the user has never rated).
    private static String bestUserGenre(List<String> rowGenres, Map<String, Double> userGenres) {
        if (rowGenres == null || rowGenres.isEmpty() || userGenres.isEmpty()) return null;
        String best = null;
        double bestCount = -1.0;
        for (String g : rowGenres) {
            if (g == null || g.isBlank()) continue;
            Double c = userGenres.get(g);
            if (c == null) continue;
            if (c > bestCount) {
                bestCount = c;
                best = g;
            }
        }
        return best;
    }

    private void enqueue(String userId) {
        computeEnqueuer.enqueue(userId);
    }

    private boolean isStale(List<UserCandidatePool> pool) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(staleTtlHours);
        LocalDateTime newest = pool.stream()
                .map(UserCandidatePool::getComputedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return newest == null || newest.isBefore(cutoff);
    }

    private void triggerRefillIfDepleted(String userId, List<UserCandidatePool> pool, Set<Integer> shown) {
        if (shown.isEmpty() || pool.isEmpty()) return;
        Set<Integer> poolIds = pool.stream().map(UserCandidatePool::getIgdbId).collect(Collectors.toSet());
        int overlap = 0;
        for (Integer id : shown) {
            if (poolIds.contains(id)) overlap++;
        }
        double threshold = poolSize * (refillThresholdPct / 100.0);
        if (overlap >= threshold) {
            enqueue(userId);
        }
    }

    private List<RecommendationDTO> tier3FallbackRealtime(String bearerToken, int limit) {
        List<UserPlatformDTO> platforms = Objects.requireNonNullElseGet(
                libraryServiceClient.getPlatforms(bearerToken), List::of);
        List<UserGameDTO> ownedGames = Objects.requireNonNullElseGet(
                libraryServiceClient.getGames(bearerToken), List::of);
        Set<Integer> owned = ownedGames.stream()
                .map(UserGameDTO::getIgdbGameId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<GameDTO> popular = new ArrayList<>();
        if (platforms.isEmpty()) {
            popular.addAll(gameServiceClient.getPopularGames(null, bearerToken));
        } else {
            for (UserPlatformDTO p : platforms) {
                popular.addAll(gameServiceClient.getPopularGames(p.getPlatformName(), bearerToken));
            }
        }

        Set<Integer> seen = new HashSet<>();
        String reason = platforms.isEmpty() ? "Popular games" : "Popular on your platforms";
        List<RecommendationDTO> out = new ArrayList<>();
        for (GameDTO g : popular) {
            if (out.size() >= limit) break;
            if (g == null || g.getIgdbId() == null) continue;
            if (!seen.add(g.getIgdbId())) continue;
            if (owned.contains(g.getIgdbId())) continue;
            out.add(toDTO(g, reason, 3));
        }
        return out;
    }

    private GroupedRecommendationsResponse tier3FallbackGrouped(String bearerToken) {
        List<RecommendationDTO> popular = tier3FallbackRealtime(bearerToken, GROUPED_PER_ROW);
        RecommendationRow row = RecommendationRow.builder()
                .label("Popular on your platforms")
                .fallback(true)
                .games(popular)
                .build();
        return GroupedRecommendationsResponse.builder()
                .rows(List.of(row))
                .tier(3)
                .emptyMessage("Rate games in your library to unlock personalized recommendations.")
                .build();
    }

    private String tierReason(int tier, boolean hasPlatforms) {
        return switch (tier) {
            case 1 -> "Based on your ratings";
            case 2 -> "Popular in your genres";
            default -> hasPlatforms ? "Popular on your platforms" : "Popular games";
        };
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
