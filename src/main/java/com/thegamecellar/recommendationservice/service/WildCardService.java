package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.GameServiceClient;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

// Random pick path owned by rec-service. Game-service stays user-agnostic: rec-service
// pulls owned-igdb-ids + platform names from UserStateCache (Redis, 5min TTL) and applies
// the filter in-memory against game-service's existing /random catalog endpoint.
@Slf4j
@Service
@RequiredArgsConstructor
public class WildCardService {

    private final GameServiceClient gameServiceClient;
    private final UserStateCache userStateCache;

    public List<RecommendationDTO> getWildCard(String bearerToken, int limit) {
        String userId = currentUserId();
        Set<Integer> owned = (userId == null) ? Set.of() : userStateCache.getLibraryIgdbIds(userId, bearerToken);
        Set<String> platforms = (userId == null) ? Set.of() : userStateCache.getPlatformNames(userId, bearerToken);

        // Oversample: catalog /random ignores user state, so over-fetch to leave room for the
        // owned + platform filter to drop matches without falling under the requested limit.
        int oversample = Math.max(limit * 5, 50);
        List<GameDTO> candidates = gameServiceClient.getRandomFromCache(oversample, bearerToken);

        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(g -> g.getIgdbId() != null)
                .filter(g -> !owned.contains(g.getIgdbId()))
                .filter(g -> matchesAnyPlatform(g, platforms))
                .limit(limit)
                .map(this::toDTO)
                .toList();
    }

    private static boolean matchesAnyPlatform(GameDTO game, Set<String> userPlatforms) {
        if (userPlatforms.isEmpty()) return true;
        if (game.getPlatforms() == null) return false;
        return game.getPlatforms().stream().anyMatch(userPlatforms::contains);
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken) {
            return JwtUtils.getUserId(auth);
        }
        return null;
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
