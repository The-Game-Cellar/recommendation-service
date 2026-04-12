package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.GameServiceClient;
import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WildCardService {

    private final GameServiceClient gameServiceClient;
    private final LibraryServiceClient libraryServiceClient;

    public List<RecommendationDTO> getWildCard(String bearerToken, int limit) {
        Set<Integer> ownedGameIds = libraryServiceClient.getGames(bearerToken).stream()
                .filter(g -> g.getRawgGameId() != null)
                .map(UserGameDTO::getRawgGameId)
                .collect(Collectors.toSet());

        Set<String> userPlatforms = libraryServiceClient.getPlatforms(bearerToken).stream()
                .filter(p -> p.getPlatformName() != null)
                .map(UserPlatformDTO::getPlatformName)
                .collect(Collectors.toSet());

        List<GameDTO> candidates = new ArrayList<>();
        if (userPlatforms.isEmpty()) {
            candidates.addAll(gameServiceClient.getPopularGames(null));
        } else {
            for (String platform : userPlatforms) {
                candidates.addAll(gameServiceClient.getPopularGames(platform));
            }
        }

        Set<Integer> seen = new HashSet<>();
        List<GameDTO> filtered = candidates.stream()
                .filter(g -> g != null && g.getRawgId() != null)
                .filter(g -> seen.add(g.getRawgId()))
                .filter(g -> !ownedGameIds.contains(g.getRawgId()))
                .collect(Collectors.toList());

        Collections.shuffle(filtered);

        int safeLimit = Math.max(0, Math.min(limit, filtered.size()));
        return filtered.subList(0, safeLimit).stream()
                .map(g -> RecommendationDTO.builder()
                        .rawgId(g.getRawgId())
                        .name(g.getName())
                        .rating(g.getRating())
                        .backgroundImage(g.getBackgroundImage())
                        .genres(g.getGenres())
                        .platforms(g.getPlatforms())
                        .reason("Wild Card - something different")
                        .tier(null)
                        .build())
                .toList();
    }
}
