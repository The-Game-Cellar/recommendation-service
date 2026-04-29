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
import java.util.Objects;
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
        List<UserGameDTO> allGames = Objects.requireNonNullElseGet(libraryServiceClient.getGames(bearerToken), List::of);
        List<UserGameDTO> ratedGames = allGames.stream()
                .filter(g -> g.getRating() != null)
                .toList();

        Set<Integer> ownedGameIds = allGames.stream()
                .map(UserGameDTO::getIgdbGameId)
                .collect(Collectors.toSet());

        List<UserPlatformDTO> platformList = Objects.requireNonNullElseGet(libraryServiceClient.getPlatforms(bearerToken), List::of);
        Set<String> userPlatforms = platformList.stream()
                .map(UserPlatformDTO::getPlatformName)
                .collect(Collectors.toSet());

        RecommendationTier tier = TierSelector.select(ratedGames.size());

        return switch (tier) {
            case ONE -> getTier1(ratedGames, ownedGameIds, userPlatforms, limit, bearerToken);
            case TWO -> getTier2(ratedGames, ownedGameIds, userPlatforms, limit, bearerToken);
            case THREE -> getTier3(ownedGameIds, userPlatforms, limit, bearerToken);
        };
    }

    private List<RecommendationDTO> getTier1(List<UserGameDTO> ratedGames,
                                              Set<Integer> ownedGameIds,
                                              Set<String> userPlatforms,
                                              int limit,
                                              String bearerToken) {
        Map<String, Double> genreProfile = UserProfileBuilder.build(ratedGames);

        // Weighted random sampling (Efraimidis-Spirakis A-Res) — higher-rated genres appear more
        // often but all genres have a chance, preserving variety across requests. Bound at 8 to
        // limit fanout to Game Service.
        // TODO (post-MVP): see [[Multi-Dimensional Recommendation Algorithm]] — extend to theme + tag.
        // TODO (post-MVP): extend Game Service to accept multiple genres in one request to collapse this loop.
        List<String> genresToSearch = UserProfileBuilder.sampleWeighted(genreProfile, 8);
        List<GameDTO> candidates = new ArrayList<>();
        for (String genre : genresToSearch) {
            int page = ThreadLocalRandom.current().nextInt(0, 20);
            List<GameDTO> results = gameServiceClient.searchByGenre(genre, null, page, bearerToken, true);
            candidates.addAll(results);
        }

        // Deduplicate, filter by platform, exclude owned, score and sort
        Set<Integer> seen = new HashSet<>();
        List<GameDTO> filtered = candidates.stream()
                .filter(Objects::nonNull)
                .filter(g -> g.getIgdbId() != null)
                .filter(g -> seen.add(g.getIgdbId()))
                .filter(g -> !ownedGameIds.contains(g.getIgdbId()))
                .filter(g -> matchesAnyPlatform(g, userPlatforms))
                .toList();

        // Fallback: if all genre searches returned nothing (cache sparse),
        // serve popular games so the dashboard is never empty.
        if (filtered.isEmpty()) {
            log.warn("Tier 1 genre search returned no candidates, falling back to popular games");
            return getTier3(ownedGameIds, userPlatforms, limit, bearerToken);
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
                                              int limit,
                                              String bearerToken) {
        // Build same weighted profile as Tier 1, just smaller (Tier 2 users have few ratings).
        // Weighted sampling capped at 5 — favours higher-rated genres but keeps variety across requests.
        // TODO (post-MVP): see [[Multi-Dimensional Recommendation Algorithm]] — extend to theme + tag.
        // TODO (post-MVP): extend Game Service to accept multiple genres in one request to collapse this loop.
        Map<String, Double> genreProfile = UserProfileBuilder.build(ratedGames);
        List<String> genresToSearch = UserProfileBuilder.sampleWeighted(genreProfile, 5);

        List<GameDTO> candidates = new ArrayList<>();
        for (String genre : genresToSearch) {
            int page = ThreadLocalRandom.current().nextInt(0, 20);
            candidates.addAll(gameServiceClient.searchByGenre(genre, null, page, bearerToken, true));
        }

        Set<Integer> seen = new HashSet<>();
        List<GameDTO> filtered = candidates.stream()
                .filter(Objects::nonNull)
                .filter(g -> g.getIgdbId() != null)
                .filter(g -> seen.add(g.getIgdbId()))
                .filter(g -> !ownedGameIds.contains(g.getIgdbId()))
                .filter(g -> matchesAnyPlatform(g, userPlatforms))
                .collect(Collectors.toList());

        // Fallback: if genre search yields nothing (e.g. Game Service degraded), use popular games
        if (filtered.isEmpty()) {
            log.warn("Tier 2 genre search returned no candidates, falling back to popular games");
            return getTier3(ownedGameIds, userPlatforms, limit, bearerToken);
        }

        Collections.shuffle(filtered);

        return filtered.subList(0, Math.min(limit, filtered.size())).stream()
                .map(g -> toDTO(g, "Popular in your genres", 2))
                .toList();
    }

    private List<RecommendationDTO> getTier3(Set<Integer> ownedGameIds,
                                              Set<String> userPlatforms,
                                              int limit,
                                              String bearerToken) {
        List<GameDTO> popular = new ArrayList<>();

        if (userPlatforms.isEmpty()) {
            // No platforms set — fall back to globally popular
            popular.addAll(gameServiceClient.getPopularGames(null, bearerToken));
        } else {
            for (String platform : userPlatforms) {
                popular.addAll(gameServiceClient.getPopularGames(platform, bearerToken));
            }
        }

        Set<Integer> seen = new HashSet<>();
        List<GameDTO> filtered = popular.stream()
                .filter(Objects::nonNull)
                .filter(g -> g.getIgdbId() != null)
                .filter(g -> seen.add(g.getIgdbId()))
                .filter(g -> !ownedGameIds.contains(g.getIgdbId()))
                .collect(Collectors.toList());

        Collections.shuffle(filtered);

        String reason = userPlatforms.isEmpty() ? "Popular games" : "Popular on your platforms";
        return filtered.subList(0, Math.min(limit, filtered.size())).stream()
                .map(g -> toDTO(g, reason, 3))
                .toList();
    }

    private boolean matchesAnyPlatform(GameDTO game, Set<String> userPlatforms) {
        if (userPlatforms.isEmpty()) return true;
        if (game.getPlatforms() == null) return false;
        return game.getPlatforms().stream().anyMatch(userPlatforms::contains);
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
