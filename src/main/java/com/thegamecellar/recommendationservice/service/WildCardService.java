package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.GameServiceClient;
import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WildCardService {

    private final GameServiceClient gameServiceClient;
    private final LibraryServiceClient libraryServiceClient;

    public List<RecommendationDTO> getWildCard(String bearerToken, int limit) {
        Set<Integer> ownedGameIds = libraryServiceClient.getGames(bearerToken).stream()
                .filter(g -> g.getIgdbGameId() != null)
                .map(UserGameDTO::getIgdbGameId)
                .collect(Collectors.toSet());

        List<GameDTO> candidates = gameServiceClient.getRandomFromCache(limit * 3, bearerToken);

        Set<Integer> seen = new HashSet<>();
        List<RecommendationDTO> results = candidates.stream()
                .filter(g -> g != null && g.getIgdbId() != null)
                .filter(g -> seen.add(g.getIgdbId()))
                .filter(g -> !ownedGameIds.contains(g.getIgdbId()))
                .limit(limit)
                .map(this::toDTO)
                .collect(Collectors.toList());

        return results;
    }

    private RecommendationDTO toDTO(GameDTO game) {
        return RecommendationDTO.builder()
                .igdbId(game.getIgdbId())
                .name(game.getName())
                .rating(game.getRating())
                .backgroundImage(game.getBackgroundImage())
                .genres(game.getGenres())
                .platforms(game.getPlatforms())
                .reason("Wild Card - something different")
                .tier(null)
                .build();
    }
}
