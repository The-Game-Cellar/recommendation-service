package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.GameServiceClient;
import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import com.thegamecellar.recommendationservice.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// /similar = pre-computed catalog answer from game-service (game_similarities). Same answer
// for every viewer. /because-you-liked composes /similar with a per-user library + platform
// filter (rec-service tier-of-responsibility: anything user-state-aware lives here).
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarGameService {

    private final GameServiceClient gameServiceClient;
    private final LibraryServiceClient libraryServiceClient;
    private final UserStateCache userStateCache;

    public List<RecommendationDTO> getSimilar(Integer igdbId, String bearerToken, int limit) {
        GameDTO source = gameServiceClient.getGameById(igdbId, bearerToken);
        if (source == null) return Collections.emptyList();
        String reason = "Similar to " + (source.getName() == null ? "this game" : source.getName());
        List<GameDTO> picks = gameServiceClient.getSimilarGames(igdbId, limit, bearerToken);
        return picks.stream()
                .filter(g -> g != null && g.getIgdbId() != null && !g.getIgdbId().equals(igdbId))
                .map(g -> toDTO(g, reason))
                .toList();
    }

    public List<RecommendationDTO> getBecauseYouLiked(Integer igdbId, String bearerToken, int limit) {
        GameDTO source = gameServiceClient.getGameById(igdbId, bearerToken);
        if (source == null) return Collections.emptyList();
        String reason = "Because you liked " + (source.getName() == null ? "this game" : source.getName());

        String userId = currentUserId();
        Set<Integer> ownedGameIds = userId != null
                ? userStateCache.getLibraryIgdbIds(userId, bearerToken)
                : getOwnedGameIds(bearerToken);
        Set<String> userPlatforms = userId != null
                ? userStateCache.getPlatformNames(userId, bearerToken)
                : getUserPlatforms(bearerToken);

        // Over-fetch so library + platform filter still leaves us with `limit` results.
        List<GameDTO> picks = gameServiceClient.getSimilarGames(igdbId, Math.max(limit * 3, 30), bearerToken);
        return picks.stream()
                .filter(g -> g != null && g.getIgdbId() != null)
                .filter(g -> !g.getIgdbId().equals(igdbId))
                .filter(g -> !ownedGameIds.contains(g.getIgdbId()))
                .filter(g -> matchesAnyPlatform(g, userPlatforms))
                .limit(limit)
                .map(g -> toDTO(g, reason))
                .toList();
    }

    private String currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken) {
            return JwtUtils.getUserId(auth);
        }
        return null;
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
