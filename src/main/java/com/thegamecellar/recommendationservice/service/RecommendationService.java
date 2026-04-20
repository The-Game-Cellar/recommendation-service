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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
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
        // Fetch game details for rated games to build genre profile.
        // Uses explicit loop instead of Collectors.toMap — toMap rejects null values and throws NPE
        // when a fetch fails, which would crash Tier 1 before the Tier 3 fallback is reached.
        Map<Integer, GameDTO> gameDetails = new HashMap<>();
        for (UserGameDTO g : ratedGames) {
            try {
                GameDTO dto = gameServiceClient.getGameById(g.getRawgGameId());
                if (dto != null) {
                    gameDetails.put(g.getRawgGameId(), dto);
                }
            } catch (Exception ex) {
                log.warn("Could not fetch details for game {}, skipping", g.getRawgGameId());
            }
        }

        Map<String, Double> genreProfile = UserProfileBuilder.build(ratedGames, gameDetails);
        log.info("Tier 1 genre profile: {}", genreProfile);

       /*  Cap at top 8 genres by weight to bound fanout.
         TODO (post-MVP): Replace hard cap with weighted random genre sampling — higher-rated genres
          should appear more often but all genres should have a chance to contribute, not just top N.

          Consider pool-size target as a stopping condition instead of a fixed genre count. */
        /* TODO (post-MVP): Extend Game Service to accept multiple genres in one request
            so we can replace this loop with a single call. Requires collaboration with Game Service.*/
        List<String> genresToSearch = genreProfile.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(8)
                .map(Map.Entry::getKey)
                .toList();
        log.info("Tier 1 searching genres: {}", genresToSearch);

        List<GameDTO> candidates = new ArrayList<>();
        for (String genre : genresToSearch) {
            int page = ThreadLocalRandom.current().nextInt(0, 20);
            List<GameDTO> results = gameServiceClient.searchByGenre(genre, null, page);
            log.info("Genre '{}' returned {} candidates", genre, results.size());
            candidates.addAll(results);
        }

        // Deduplicate, filter by platform, exclude owned, score and sort
        Set<Integer> seen = new HashSet<>();
        List<GameDTO> filtered = candidates.stream()
                .filter(g -> seen.add(g.getRawgId()))
                .filter(g -> !ownedGameIds.contains(g.getRawgId()))
                .filter(g -> matchesAnyPlatform(g, userPlatforms))
                .toList();

        // Fallback: if all genre searches returned nothing (cache sparse + RAWG degraded),
        // serve popular games so the dashboard is never empty.
        if (filtered.isEmpty()) {
            log.warn("Tier 1 genre search returned no candidates, falling back to popular games");
            return getTier3(ownedGameIds, userPlatforms, limit);
        }

        List<GameDTO> scored = filtered.stream()
                .sorted(Comparator.comparingDouble(g -> -SimilarityScorer.score(g, genreProfile)))
                .toList();

        List<GameDTO> pool = new ArrayList<>(scored.subList(0, Math.min(100, scored.size())));
        Collections.shuffle(pool);

        return pool.subList(0, Math.min(limit, pool.size())).stream()
                .map(g -> toDTO(g, "Based on your ratings", 1))
                .toList();
    }

    private List<RecommendationDTO> getTier2(List<UserGameDTO> ratedGames,
                                              Set<Integer> ownedGameIds,
                                              Set<String> userPlatforms,
                                              int limit) {
        // Build genre set from the few rated games
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

        // Cap at top 5 genres — Tier-2 users have few ratings so genre set is naturally small,
        // but we cap defensively to bound fanout.
        // TODO (post-MVP): Replace hard cap with weighted random genre sampling.
        // TODO (post-MVP): Extend Game Service to accept multiple genres in one request
        //  so we can replace this loop with a single call. Requires collaboration with Game Service.
        List<String> genresToSearch = preferredGenres.stream().limit(5).toList();

        List<GameDTO> candidates = new ArrayList<>();
        for (String genre : genresToSearch) {
            int page = ThreadLocalRandom.current().nextInt(0, 20);
            candidates.addAll(gameServiceClient.searchByGenre(genre, null, page));
        }

        Set<Integer> seen = new HashSet<>();
        List<GameDTO> filtered = candidates.stream()
                .filter(g -> seen.add(g.getRawgId()))
                .filter(g -> !ownedGameIds.contains(g.getRawgId()))
                .filter(g -> matchesAnyPlatform(g, userPlatforms))
                .collect(Collectors.toList());

        // Fallback: if genre search yields nothing (e.g. Game Service degraded), use popular games
        if (filtered.isEmpty()) {
            log.warn("Tier 2 genre search returned no candidates, falling back to popular games");
            return getTier3(ownedGameIds, userPlatforms, limit);
        }

        Collections.shuffle(filtered);

        return filtered.subList(0, Math.min(limit, filtered.size())).stream()
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
                .collect(Collectors.toList());

        Collections.shuffle(filtered);

        return filtered.subList(0, Math.min(limit, filtered.size())).stream()
                .map(g -> toDTO(g, "Popular on your platforms", 3))
                .toList();
    }

    private boolean matchesAnyPlatform(GameDTO game, Set<String> userPlatforms) {
        if (userPlatforms.isEmpty()) return true;
        if (game.getPlatforms() == null) return false;
        return game.getPlatforms().stream().anyMatch(userPlatforms::contains);
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
