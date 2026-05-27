package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.GameServiceClient;
import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.repository.UserCandidatePoolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    private static final int POOL_SIZE = 1000;
    private static final int REFILL_PCT = 50;
    private static final long STALE_HOURS = 24;

    @Mock private UserCandidatePoolRepository poolRepository;
    @Mock private UserProfileCache profileCache;
    @Mock private ComputeEnqueuer computeEnqueuer;
    @Mock private GameServiceClient gameServiceClient;
    @Mock private LibraryServiceClient libraryServiceClient;

    private RecommendationService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationService(
                poolRepository, profileCache, computeEnqueuer, gameServiceClient, libraryServiceClient,
                POOL_SIZE, REFILL_PCT, STALE_HOURS);
    }

    @Test
    void coldStart_emptyPool_enqueuesAndServesTier3() {
        when(poolRepository.findByUserId("u1")).thenReturn(List.of());
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        when(gameServiceClient.getPopularGames(eq("PC"), anyString()))
                .thenReturn(List.of(game(1, "Popular Game", "Action")));

        List<RecommendationDTO> result = service.getPersonalized("u1", "token", 10, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTier()).isEqualTo(3);
        verify(computeEnqueuer).enqueue("u1");
    }

    @Test
    void warmPool_consecutiveCalls_rotateOrdering() {
        List<UserCandidatePool> pool = poolOfSize(50, "u1");
        when(poolRepository.findByUserId("u1")).thenReturn(pool);

        List<Integer> first = service.getPersonalized("u1", "token", 10, Set.of())
                .stream().map(RecommendationDTO::getIgdbId).toList();

        boolean anyDifferent = false;
        for (int i = 0; i < 15; i++) {
            List<Integer> next = service.getPersonalized("u1", "token", 10, Set.of())
                    .stream().map(RecommendationDTO::getIgdbId).toList();
            if (!first.equals(next)) {
                anyDifferent = true;
                break;
            }
        }
        assertThat(anyDifferent).as("jitter must produce different ordering across calls").isTrue();
    }

    @Test
    void warmPool_shownPenaltyPushesShownIdsDown() {
        List<UserCandidatePool> pool = poolOfSize(20, "u1");
        when(poolRepository.findByUserId("u1")).thenReturn(pool);

        // Mark the top-3-by-base-score (ids 0,1,2) as recently shown -> penalty drops them.
        Set<Integer> shown = Set.of(0, 1, 2);
        int shownInTop3 = 0;
        int trials = 20;
        for (int i = 0; i < trials; i++) {
            List<Integer> top3 = service.getPersonalized("u1", "token", 3, shown)
                    .stream().map(RecommendationDTO::getIgdbId).toList();
            for (Integer id : top3) {
                if (shown.contains(id)) shownInTop3++;
            }
        }
        // 0.40 penalty vs 0.08 jitter -> shown ids should rarely surface in top-3.
        assertThat(shownInTop3).as("shown ids should be rare in top-3").isLessThan(trials);
    }

    @Test
    void warmPool_refillTriggersAt50PercentDepletion() {
        List<UserCandidatePool> pool = poolOfSize(100, "u1");
        when(poolRepository.findByUserId("u1")).thenReturn(pool);

        // 50% of POOL_SIZE = 500. We need 500 ids in shown that are also in the pool.
        // Pool ids run 0..99, so use ids 0..499 (only 0..99 intersect; not enough).
        // Use a pool that matches POOL_SIZE for clean intersection counting.
        List<UserCandidatePool> bigPool = poolOfSize(POOL_SIZE, "u1");
        when(poolRepository.findByUserId("u1")).thenReturn(bigPool);
        Set<Integer> shown = new HashSet<>(IntStream.range(0, 500).boxed().toList());

        service.getPersonalized("u1", "token", 10, shown);

        verify(computeEnqueuer, atLeastOnce()).enqueue("u1");
    }

    @Test
    void warmPool_belowRefillThreshold_doesNotEnqueue() {
        List<UserCandidatePool> bigPool = poolOfSize(POOL_SIZE, "u1");
        when(poolRepository.findByUserId("u1")).thenReturn(bigPool);
        Set<Integer> shown = new HashSet<>(IntStream.range(0, 100).boxed().toList()); // 10% depletion

        service.getPersonalized("u1", "token", 10, shown);

        verify(computeEnqueuer, never()).enqueue("u1");
    }

    @Test
    void warmPool_stalePoolEnqueues() {
        List<UserCandidatePool> pool = poolOfSize(20, "u1");
        LocalDateTime ancient = LocalDateTime.now().minusDays(2);
        pool.forEach(r -> r.setComputedAt(ancient));
        when(poolRepository.findByUserId("u1")).thenReturn(pool);

        service.getPersonalized("u1", "token", 10, Set.of());

        verify(computeEnqueuer, times(1)).enqueue("u1");
    }

    private List<UserCandidatePool> poolOfSize(int n, String userId) {
        List<UserCandidatePool> pool = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            pool.add(UserCandidatePool.builder()
                    .userId(userId)
                    .igdbId(i)
                    .baseScore(BigDecimal.valueOf(1.0 - (double) i / n).setScale(4, java.math.RoundingMode.HALF_UP))
                    .tier((short) 1)
                    .name("Game " + i)
                    .backgroundImage(null)
                    .rating(BigDecimal.valueOf(8.0))
                    .genres(List.of(i % 2 == 0 ? "Action" : "RPG"))
                    .platforms(List.of("PC"))
                    .computedAt(LocalDateTime.now())
                    .build());
        }
        return pool;
    }

    private UserPlatformDTO platform(String name) {
        UserPlatformDTO p = new UserPlatformDTO();
        p.setPlatformName(name);
        return p;
    }

    private GameDTO game(int igdbId, String name, String... genres) {
        GameDTO g = new GameDTO();
        g.setIgdbId(igdbId);
        g.setName(name);
        g.setRating(BigDecimal.valueOf(8.0));
        g.setPlatforms(List.of("PC"));
        if (genres.length > 0) g.setGenres(List.of(genres));
        return g;
    }
}
