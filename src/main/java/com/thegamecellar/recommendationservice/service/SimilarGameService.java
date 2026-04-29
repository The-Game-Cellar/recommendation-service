package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.algorithm.SimilarityScorer;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarGameService {

    private final GameServiceClient gameServiceClient;
    private final LibraryServiceClient libraryServiceClient;

    public List<RecommendationDTO> getSimilar(Integer igdbId, String bearerToken, int limit) {
        return getRecommendationsBasedOnGame(igdbId, bearerToken, limit, "Similar to ");
    }

    public List<RecommendationDTO> getBecauseYouLiked(Integer igdbId, String bearerToken, int limit) {
        return getRecommendationsBasedOnGame(igdbId, bearerToken, limit, "Because you liked ");
    }

    private List<RecommendationDTO> getRecommendationsBasedOnGame(Integer igdbId, String bearerToken,
                                                                   int limit, String reasonPrefix) {
        GameDTO sourceGame = gameServiceClient.getGameById(igdbId, bearerToken);
        if (sourceGame == null || sourceGame.getGenres() == null || sourceGame.getGenres().isEmpty()) {
            log.warn("Could not find game {} or it has no genres", igdbId);
            return Collections.emptyList();
        }

        Set<Integer> ownedGameIds = getOwnedGameIds(bearerToken);
        Set<String> userPlatforms = getUserPlatforms(bearerToken);

        Map<String, Double> genreProfile = sourceGame.getGenres().stream()
                .distinct()
                .collect(Collectors.toMap(g -> g, g -> 1.0));

        List<GameDTO> candidates = fetchCandidates(new ArrayList<>(genreProfile.keySet()), bearerToken);

        return rankAndSlice(candidates, ownedGameIds, userPlatforms, genreProfile, igdbId,
                reasonPrefix + sourceGame.getName(), limit);
    }

    private List<GameDTO> fetchCandidates(List<String> genres, String bearerToken) {
        List<GameDTO> candidates = new ArrayList<>();
        for (String genre : genres) {
            if (genre != null && !genre.isBlank()) {
                int page = ThreadLocalRandom.current().nextInt(0, 20);
                candidates.addAll(gameServiceClient.searchByGenre(genre, null, page, bearerToken, true));
            }
        }
        return candidates;
    }

    private List<RecommendationDTO> rankAndSlice(List<GameDTO> candidates,
                                                  Set<Integer> ownedGameIds,
                                                  Set<String> userPlatforms,
                                                  Map<String, Double> genreProfile,
                                                  Integer excludeId,
                                                  String reason,
                                                  int limit) {
        Set<Integer> seen = new HashSet<>();
        List<GameDTO> filtered = candidates.stream()
                .filter(g -> g != null && g.getIgdbId() != null)
                .filter(g -> seen.add(g.getIgdbId()))
                .filter(g -> !g.getIgdbId().equals(excludeId))
                .filter(g -> !ownedGameIds.contains(g.getIgdbId()))
                .filter(g -> matchesAnyPlatform(g, userPlatforms))
                .toList();

        List<GameDTO> scored = filtered.stream()
                .sorted(Comparator.comparingDouble(g -> -SimilarityScorer.score(g, genreProfile)))
                .toList();

        List<GameDTO> pool = new ArrayList<>(scored.subList(0, Math.min(20, scored.size())));
        Collections.shuffle(pool);

        return pool.subList(0, Math.min(limit, pool.size())).stream()
                .map(g -> toDTO(g, reason))
                .toList();
    }

    private Set<Integer> getOwnedGameIds(String bearerToken) {
        return libraryServiceClient.getGames(bearerToken).stream()
                .map(UserGameDTO::getIgdbGameId)
                .collect(Collectors.toSet());
    }

    private Set<String> getUserPlatforms(String bearerToken) {
        return libraryServiceClient.getPlatforms(bearerToken).stream()
                .map(UserPlatformDTO::getPlatformName)
                .collect(Collectors.toSet());
    }

    private boolean matchesAnyPlatform(GameDTO game, Set<String> userPlatforms) {
        if (userPlatforms.isEmpty()) return true;
        if (game.getPlatforms() == null) return false;
        return game.getPlatforms().stream().anyMatch(userPlatforms::contains);
    }

    private RecommendationDTO toDTO(GameDTO game, String reason) {
        return RecommendationDTO.builder()
                .igdbId(game.getIgdbId())
                .name(game.getName())
                .rating(game.getRating())
                .backgroundImage(game.getBackgroundImage())
                .genres(game.getGenres())
                .platforms(game.getPlatforms())
                .reason(reason)
                .tier(null)
                .build();
    }
}