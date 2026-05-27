package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.model.dto.BecauseYouLikedDTO;
import com.thegamecellar.recommendationservice.model.dto.DashboardDTO;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.repository.UserCandidatePoolRepository;
import com.thegamecellar.recommendationservice.util.JwtUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class DashboardService {

    private static final long SUB_PAYLOAD_TIMEOUT_SECONDS = 10;
    // Minimum pool overlap needed before serving BYL from the pool. Below this we fall back
    // to the slower searchByGenre path to guarantee a populated row even on edge-case seeds.
    private static final int POOL_BYL_MIN_CANDIDATES = 8;
    // Two BYL sections on the dashboard row so the layout fills the full width.
    private static final int BYL_SECTIONS = 2;
    // Send more than the narrowest viewport fits so wide screens fill the section. Frontend
    // GameScroll truncates to viewport-fit count, so this is a soft upper bound.
    private static final int BYL_GAMES_PER_SECTION = 12;
    // Same soft upper bound for the "Recommendations for you" full-row section on dashboard.
    private static final int PERSONALIZED_DASHBOARD_LIMIT = 20;

    private final RecommendationService recommendationService;
    private final WildCardService wildCardService;
    private final SimilarGameService similarGameService;
    private final LibraryServiceClient libraryServiceClient;
    private final UserCandidatePoolRepository poolRepository;
    private final ExecutorService dashboardExecutor;

    public DashboardService(RecommendationService recommendationService,
                             WildCardService wildCardService,
                             SimilarGameService similarGameService,
                             LibraryServiceClient libraryServiceClient,
                             UserCandidatePoolRepository poolRepository,
                             @Qualifier("dashboardExecutor") ExecutorService dashboardExecutor) {
        this.recommendationService = recommendationService;
        this.wildCardService = wildCardService;
        this.similarGameService = similarGameService;
        this.libraryServiceClient = libraryServiceClient;
        this.poolRepository = poolRepository;
        this.dashboardExecutor = dashboardExecutor;
    }

    public DashboardDTO getDashboard(String bearerToken) {
        return getDashboard(null, bearerToken, null);
    }

    public DashboardDTO getDashboard(String bearerToken, Set<Integer> recentlyShownIds) {
        return getDashboard(null, bearerToken, recentlyShownIds);
    }

    // Three sub-payloads (recs, wildcard, becauseYouLiked) run on the dashboard executor in parallel.
    // Total wall time bounded by the slowest sub-call. Each propagates the calling thread's
    // SecurityContext so JWT-bearer + currentUserId() still work inside the async tasks.
    public DashboardDTO getDashboard(String userId, String bearerToken, Set<Integer> recentlyShownIds) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        CompletableFuture<List<RecommendationDTO>> recsF = supplyAsync(auth, () -> {
            try {
                if (userId == null) return Collections.<RecommendationDTO>emptyList();
                return recommendationService.getPersonalized(userId, bearerToken, PERSONALIZED_DASHBOARD_LIMIT, recentlyShownIds);
            } catch (Exception ex) {
                log.warn("Personalized recommendations failed, returning empty: {}", ex.getClass().getSimpleName());
                return Collections.emptyList();
            }
        });

        CompletableFuture<List<RecommendationDTO>> wildcardF = supplyAsync(auth, () -> {
            try {
                return wildCardService.getWildCard(bearerToken, BYL_GAMES_PER_SECTION);
            } catch (Exception ex) {
                log.warn("WildCard failed, returning empty: {}", ex.getClass().getSimpleName());
                return Collections.emptyList();
            }
        });

        CompletableFuture<List<BecauseYouLikedDTO>> bylF = supplyAsync(auth, () -> {
            try {
                return getBecauseYouLiked(bearerToken);
            } catch (Exception ex) {
                log.warn("BecauseYouLiked failed, returning empty: {}", ex.getClass().getSimpleName());
                return Collections.emptyList();
            }
        });

        return DashboardDTO.builder()
                .recommendations(await(recsF, "recommendations", Collections.emptyList()))
                .wildcard(await(wildcardF, "wildcard", Collections.emptyList()))
                .becauseYouLiked(await(bylF, "becauseYouLiked", Collections.emptyList()))
                .build();
    }

    private <T> CompletableFuture<T> supplyAsync(Authentication auth,
                                                   java.util.function.Supplier<T> body) {
        return CompletableFuture.supplyAsync(() -> {
            // Async threads do not inherit SecurityContextHolder by default. Bind the request's
            // Authentication so JwtUtils.getUserId / getBearerToken still resolve.
            SecurityContextHolder.getContext().setAuthentication(auth);
            try {
                return body.get();
            } finally {
                SecurityContextHolder.clearContext();
            }
        }, dashboardExecutor);
    }

    private static <T> T await(CompletableFuture<T> future, String label, T fallback) {
        try {
            return future.get(SUB_PAYLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            log.warn("Dashboard sub-payload '{}' timed out after {}s", label, SUB_PAYLOAD_TIMEOUT_SECONDS);
            future.cancel(true);
            return fallback;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception ex) {
            log.warn("Dashboard sub-payload '{}' failed: {}", label, ex.getClass().getSimpleName());
            return fallback;
        }
    }

    // Initial dashboard load: pick BYL_SECTIONS distinct seeds so the section fills the full row.
    private List<BecauseYouLikedDTO> getBecauseYouLiked(String bearerToken) {
        return rollBecauseYouLiked(bearerToken, Set.of(), BYL_SECTIONS);
    }

    // Refresh path: roll ONE fresh seed, excluding all currently-shown seeds (current section's
    // seed + sibling section's seed) so the new pick is distinct from both.
    public List<BecauseYouLikedDTO> rollBecauseYouLiked(String bearerToken, Integer excludeSeedIgdbId) {
        Set<Integer> excludes = excludeSeedIgdbId == null ? Set.of() : Set.of(excludeSeedIgdbId);
        return rollBecauseYouLiked(bearerToken, excludes, 1);
    }

    public List<BecauseYouLikedDTO> rollBecauseYouLiked(String bearerToken,
                                                       Set<Integer> excludeSeedIgdbIds,
                                                       int count) {
        if (count <= 0) return Collections.emptyList();
        Set<Integer> excludes = excludeSeedIgdbIds == null ? Set.of() : excludeSeedIgdbIds;
        List<UserGameDTO> games = libraryServiceClient.getGames(bearerToken);
        List<UserGameDTO> eligible = games.stream()
                .filter(g -> g.getRating() != null && g.getRating() >= 7 && g.getIgdbGameId() != null)
                .filter(g -> isEligibleStatus(g.getStatus()))
                .filter(g -> !excludes.contains(g.getIgdbGameId()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        Collections.shuffle(eligible);
        List<UserGameDTO> seeds = eligible.stream().limit(count).toList();

        // Fallback: not enough rated games once excludes applied. Re-roll without excludes so
        // the user still sees content rather than an empty section.
        if (seeds.isEmpty() && !excludes.isEmpty()) {
            return rollBecauseYouLiked(bearerToken, Set.of(), count);
        }
        if (seeds.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Integer> ownedIds = games.stream()
                .map(UserGameDTO::getIgdbGameId)
                .collect(java.util.stream.Collectors.toSet());

        return seeds.stream()
                .map(seed -> buildSection(seed, bearerToken, ownedIds))
                .filter(dto -> dto != null && !dto.getRecommendations().isEmpty())
                .toList();
    }

    private BecauseYouLikedDTO buildSection(UserGameDTO seed, String bearerToken, Set<Integer> ownedIds) {
        try {
            List<RecommendationDTO> recos = fastPoolByl(seed, ownedIds, BYL_GAMES_PER_SECTION);
            if (recos.size() < POOL_BYL_MIN_CANDIDATES / 2) {
                recos = similarGameService.getBecauseYouLiked(seed.getIgdbGameId(), bearerToken, BYL_GAMES_PER_SECTION);
            }
            return BecauseYouLikedDTO.builder()
                    .basedOnIgdbId(seed.getIgdbGameId())
                    .basedOnGame(seed.getGameName())
                    .recommendations(recos)
                    .build();
        } catch (Exception ex) {
            log.warn("Failed to fetch because-you-liked for game {}, skipping seed", seed.getIgdbGameId());
            return null;
        }
    }

    // Pool-based BYL. Seed genres come from the user library row (no game-service hop). Pool
    // is already filtered to user's library + platforms by the worker, so we only need
    // genre-overlap scoring vs the seed. Returns empty when pool coverage is too sparse so
    // the caller can fall back to the slower searchByGenre path.
    private List<RecommendationDTO> fastPoolByl(UserGameDTO seed, Set<Integer> ownedIds, int limit) {
        String userId = currentUserId();
        if (userId == null) return List.of();
        List<String> seedGenres = (seed.getGenres() == null ? List.<String>of() : seed.getGenres()).stream()
                .filter(g -> g != null && !g.isBlank())
                .toList();
        if (seedGenres.isEmpty()) return List.of();

        Set<String> seedGenreSet = new HashSet<>(seedGenres);
        List<UserCandidatePool> pool = poolRepository.findByUserId(userId);
        List<Scored> candidates = new ArrayList<>();
        for (UserCandidatePool row : pool) {
            Integer id = row.getIgdbId();
            if (id == null || id.equals(seed.getIgdbGameId()) || ownedIds.contains(id)) continue;
            List<String> rowGenres = row.getGenres();
            if (rowGenres == null || rowGenres.isEmpty()) continue;
            int overlap = 0;
            for (String g : rowGenres) if (seedGenreSet.contains(g)) overlap++;
            if (overlap == 0) continue;
            double score = (double) overlap / Math.max(rowGenres.size(), seedGenreSet.size());
            candidates.add(new Scored(row, score));
        }
        if (candidates.size() < POOL_BYL_MIN_CANDIDATES) return List.of();

        candidates.sort((a, b) -> Double.compare(b.score, a.score));
        int poolSize = Math.min(20, candidates.size());
        List<Scored> top = new ArrayList<>(candidates.subList(0, poolSize));
        Collections.shuffle(top);
        return top.stream()
                .limit(limit)
                .map(s -> RecommendationDTO.builder()
                        .igdbId(s.row.getIgdbId())
                        .name(s.row.getName())
                        .rating(s.row.getRating())
                        .backgroundImage(s.row.getBackgroundImage())
                        .genres(s.row.getGenres())
                        .platforms(s.row.getPlatforms())
                        .reason("Because you liked " + (seed.getGameName() == null ? "this game" : seed.getGameName()))
                        .tier(null)
                        .build())
                .toList();
    }

    private record Scored(UserCandidatePool row, double score) {}

    // Same eligible set as RecommendationService scoring path. DROPPED + WISHLIST excluded:
    // DROPPED = explicit dislike signal; WISHLIST = not played, rating is aspirational guess.
    // Null status kept eligible for legacy library rows without a status field.
    private static boolean isEligibleStatus(String status) {
        if (status == null) return true;
        return switch (status) {
            case "COMPLETED", "PLAYING", "BACKLOG", "DUSTY" -> true;
            default -> false;
        };
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken) {
            return JwtUtils.getUserId(auth);
        }
        return null;
    }
}
