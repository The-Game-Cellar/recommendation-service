package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.model.dto.BecauseYouLikedDTO;
import com.thegamecellar.recommendationservice.model.dto.DashboardDTO;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final RecommendationService recommendationService;
    private final WildCardService wildCardService;
    private final SimilarGameService similarGameService;
    private final LibraryServiceClient libraryServiceClient;

    public DashboardDTO getDashboard(String bearerToken) {
        return getDashboard(bearerToken, null);
    }

    /**
     * Recently-shown ids feed the soft score penalty in the personalized section only. Wild Card
     * is already random-per-call and Because You Liked is seed-driven, so neither benefits from
     * the same recency input and they intentionally ignore it.
     */
    public DashboardDTO getDashboard(String bearerToken, Set<Integer> recentlyShownIds) {
        List<RecommendationDTO> recommendations;
        try {
            recommendations = recommendationService.getPersonalized(bearerToken, 10, recentlyShownIds);
        } catch (Exception ex) {
            log.warn("Personalized recommendations failed, returning empty: {}", ex.getMessage());
            recommendations = Collections.emptyList();
        }

        List<RecommendationDTO> wildcard;
        try {
            wildcard = wildCardService.getWildCard(bearerToken, 5);
        } catch (Exception ex) {
            log.warn("WildCard failed, returning empty: {}", ex.getMessage());
            wildcard = Collections.emptyList();
        }

        List<BecauseYouLikedDTO> becauseYouLiked = getBecauseYouLiked(bearerToken);

        return DashboardDTO.builder()
                .recommendations(recommendations)
                .wildcard(wildcard)
                .becauseYouLiked(becauseYouLiked)
                .build();
    }

    private List<BecauseYouLikedDTO> getBecauseYouLiked(String bearerToken) {
        List<UserGameDTO> games = libraryServiceClient.getGames(bearerToken);

        // Shuffle eligible games so the seed rotates across requests rather than always being the
        // same highest-rated game (insertion-order bias).  Pick 1 seed for the dashboard.
        List<UserGameDTO> eligible = games.stream()
                .filter(g -> g.getRating() != null && g.getRating() >= 8 && g.getIgdbGameId() != null)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        Collections.shuffle(eligible);
        List<UserGameDTO> seeds = eligible.stream().limit(1).toList();

        if (seeds.isEmpty()) {
            return Collections.emptyList();
        }

        return seeds.stream()
                .map(seed -> {
                    try {
                        List<RecommendationDTO> recos = similarGameService.getBecauseYouLiked(
                                seed.getIgdbGameId(), bearerToken, 5);
                        return BecauseYouLikedDTO.builder()
                                .basedOnIgdbId(seed.getIgdbGameId())
                                .basedOnGame(seed.getGameName())
                                .recommendations(recos)
                                .build();
                    } catch (Exception ex) {
                        log.warn("Failed to fetch because-you-liked for game {}, skipping seed", seed.getIgdbGameId());
                        return null;
                    }
                })
                .filter(dto -> dto != null && !dto.getRecommendations().isEmpty())
                .toList();
    }
}