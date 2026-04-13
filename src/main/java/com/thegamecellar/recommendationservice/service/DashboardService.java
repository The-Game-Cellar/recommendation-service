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

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final RecommendationService recommendationService;
    private final WildCardService wildCardService;
    private final SimilarGameService similarGameService;
    private final LibraryServiceClient libraryServiceClient;

    public DashboardDTO getDashboard(String bearerToken) {
        List<RecommendationDTO> recommendations = recommendationService.getPersonalized(bearerToken, 10);
        List<RecommendationDTO> wildcard = wildCardService.getWildCard(bearerToken, 5);
        List<BecauseYouLikedDTO> becauseYouLiked = getBecauseYouLiked(bearerToken);

        return DashboardDTO.builder()
                .recommendations(recommendations)
                .wildcard(wildcard)
                .becauseYouLiked(becauseYouLiked)
                .build();
    }

    private List<BecauseYouLikedDTO> getBecauseYouLiked(String bearerToken) {
        List<UserGameDTO> games = libraryServiceClient.getGames(bearerToken);

        // Pick up to 3 highly-rated games to base the "because you liked" section on
        List<UserGameDTO> seeds = games.stream()
                .filter(g -> g.getRating() != null && g.getRating() >= 8 && g.getRawgGameId() != null)
                .limit(3)
                .toList();

        if (seeds.isEmpty()) {
            return Collections.emptyList();
        }

        return seeds.stream()
                .map(seed -> {
                    try {
                        List<RecommendationDTO> recos = similarGameService.getBecauseYouLiked(
                                seed.getRawgGameId(), bearerToken, 5);
                        return BecauseYouLikedDTO.builder()
                                .basedOnRawgId(seed.getRawgGameId())
                                .basedOnGame(seed.getGameName())
                                .recommendations(recos)
                                .build();
                    } catch (Exception ex) {
                        log.warn("Failed to fetch because-you-liked for game {}, skipping seed", seed.getRawgGameId());
                        return null;
                    }
                })
                .filter(dto -> dto != null && !dto.getRecommendations().isEmpty())
                .toList();
    }
}