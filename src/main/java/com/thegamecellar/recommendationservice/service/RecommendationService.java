package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.algorithm.RecommendationTier;
import com.thegamecellar.recommendationservice.algorithm.SimilarityScorer;
import com.thegamecellar.recommendationservice.algorithm.TierSelector;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final GameServiceClient gameServiceClient;
    private final LibraryServiceClient libraryServiceClient;

    public List<RecommendationDTO> getPersonalized(String bearerToken, int limit) {
        List<UserGameDTO> allGames = libraryServiceClient.getGames(bearerToken);
        List<UserGameDTO> ratedGames = allGames.stream()
                .filter(g -> g.getRating() != null)
                .toList();

        Set<Integer> ownedGameIds = allGames.stream()
                .map(UserGameDTO::getRawgGameId)
                .collect(Collectors.toSet());

        Set<String> userPlatforms = libraryServiceClient.getPlatforms(bearerToken).stream()
                .map(UserPlatformDTO::getPlatformName)
                .collect(Collectors.toSet());

        RecommendationTier tier = TierSelector.select(ratedGames.size());
        log.info("Selected tier {} for user with {} rated games", tier, ratedGames.size());

        return switch (tier) {
            case ONE -> getTier1(ratedGames, ownedGameIds, userPlatforms, limit);
            case TWO -> getTier2(ratedGames, ownedGameIds, userPlatforms, limit);
            case THREE -> getTier3(ownedGameIds, userPlatforms, limit);
        };
    }

    private List<RecommendationDTO> getTier1(List<UserGameDTO> ratedGames,
                                              Set<Integer> ownedGameIds,
                                              Set<String> userPlatforms,
                                              int limit) {
        // Fetch game details for rated games to build genre profile
        Map<Integer, GameDTO> gameDetails = ratedGames.stream()
                .collect(Collectors.toMap(
                        UserGameDTO::getRawgGameId,
                        g -> {
                            try {
                                return gameServiceClient.getGameById(g.getRawgGameId());
                            } catch (Exception ex) {
                                log.warn("Could not fetch details for game {}", g.getRawgGameId());
                                return null;
                            }
                        }
                ));
        gameDetails.values().removeIf(v -> v == null);

        Map<String, Double> genreProfile = UserProfileBuilder.build(ratedGames, gameDetails);

        // Get top 3 genres to search by
        List<String> topGenres = genreProfile.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();

        // Fetch candidate games for each top genre
        List<GameDTO> candidates = new ArrayList<>();
        for (String genre : topGenres) {
            candidates.addAll(gameServiceClient.searchByGenre(genre, null));
        }

        // Deduplicate, filter by platform, exclude owned, score and sort
        Set<Integer> seen = new HashSet<>();
        List<GameDTO> filtered = candidates.stream()
                .filter(g -> seen.add(g.getRawgId()))
                .filter(g -> !ownedGameIds.contains(g.getRawgId()))
                .filter(g -> matchesAnyPlatform(g, userPlatforms))
                .toList();

        List<GameDTO> scored = filtered.stream()
                .sorted(Comparator.comparingDouble(g -> -SimilarityScorer.score(g, genreProfile)))
                .toList();

        // Shuffle top 20 for variety
        List<GameDTO> pool = new ArrayList<>(scored.subList(0, Math.min(20, scored.size())));
        Collections.shuffle(pool);

        return pool.subList(0, Math.min(limit, pool.size())).stream()
                .map(g -> toDTO(g, "Based on your ratings", 1))
                .toList();
    }

    private List<RecommendationDTO> getTier2(List<UserGameDTO> ratedGames,
                                              Set<Integer> ownedGameIds,
                                              Set<String> userPlatforms,
                                              int limit) {
        // Build a simple genre preference set from the few rated games
        Set<String> preferredGenres = ratedGames.stream()
                .map(UserGameDTO::getRawgGameId)
                .map(id -> {
                    try {
                        return gameServiceClient.getGameById(id);
                    } catch (Exception ex) {
                        return null;
                    }
                })
                .filter(g -> g != null && g.getGenres() != null)
                .flatMap(g -> g.getGenres().stream())
                .collect(Collectors.toSet());

        List<GameDTO> popular = new ArrayList<>();
        for (String platform : userPlatforms) {
            popular.addAll(gameServiceClient.getPopularGames(platform));
        }

        Set<Integer> seen = new HashSet<>();
        List<GameDTO> filtered = popular.stream()
                .filter(g -> seen.add(g.getRawgId()))
                .filter(g -> !ownedGameIds.contains(g.getRawgId()))
                .filter(g -> preferredGenres.isEmpty() || matchesAnyGenre(g, preferredGenres))
                .limit(limit)
                .toList();

        return filtered.stream()
                .map(g -> toDTO(g, "Popular in your genres", 2))
                .toList();
    }

    private List<RecommendationDTO> getTier3(Set<Integer> ownedGameIds,
                                              Set<String> userPlatforms,
                                              int limit) {
        List<GameDTO> popular = new ArrayList<>();

        if (userPlatforms.isEmpty()) {
            // No platforms set — fall back to globally popular
            popular.addAll(gameServiceClient.getPopularGames(null));
        } else {
            for (String platform : userPlatforms) {
                popular.addAll(gameServiceClient.getPopularGames(platform));
            }
        }

        Set<Integer> seen = new HashSet<>();
        List<GameDTO> filtered = popular.stream()
                .filter(g -> seen.add(g.getRawgId()))
                .filter(g -> !ownedGameIds.contains(g.getRawgId()))
                .limit(limit)
                .toList();

        return filtered.stream()
                .map(g -> toDTO(g, "Popular on your platforms", 3))
                .toList();
    }

    private boolean matchesAnyPlatform(GameDTO game, Set<String> userPlatforms) {
        if (userPlatforms.isEmpty()) return true;
        if (game.getPlatforms() == null) return false;
        return game.getPlatforms().stream().anyMatch(userPlatforms::contains);
    }

    private boolean matchesAnyGenre(GameDTO game, Set<String> genres) {
        if (game.getGenres() == null) return false;
        return game.getGenres().stream().anyMatch(genres::contains);
    }

    private RecommendationDTO toDTO(GameDTO game, String reason, int tier) {
        return RecommendationDTO.builder()
                .rawgId(game.getRawgId())
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
