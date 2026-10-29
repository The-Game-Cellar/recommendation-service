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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

// Random pick path owned by rec-service. Game-service stays user-agnostic: rec-service pulls
// owned-igdb-ids + platform names from UserStateCache (Redis, 5min TTL), asks the catalog's
// /random for the platforms the hand may come from, and drops what the user owns here.
@Slf4j
@Service
@RequiredArgsConstructor
public class WildCardService {

    // Asked per platform, so PS5 next to PC deals as much PS5 as PC instead of the catalog's mostly-PC mix.
    private static final int PER_PLATFORM = 50;
    // game-service's /random ceiling.
    private static final int MAX_DRAW = 500;

    private final GameServiceClient gameServiceClient;
    private final UserStateCache userStateCache;

    public List<RecommendationDTO> getWildCard(String bearerToken, int limit) {
        return getWildCard(bearerToken, limit, false, List.of());
    }

    public List<RecommendationDTO> getWildCard(String bearerToken, int limit, boolean allPlatforms, List<String> picked) {
        String userId = currentUserId();
        Set<Integer> owned = (userId == null) ? Set.of() : userStateCache.getLibraryIgdbIds(userId, bearerToken);
        Set<String> profile = (allPlatforms || userId == null) ? Set.of() : userStateCache.getPlatformNames(userId, bearerToken);
        Set<String> platforms = platformsToDeal(allPlatforms, cleaned(picked), profile);

        // Oversample: the catalog's random pick ignores the library, so over-fetch to leave room for the
        // owned filter to drop matches without falling under the requested limit.
        int draw = platforms.isEmpty()
                ? Math.max(limit * 5, 50)
                : Math.min(PER_PLATFORM * platforms.size(), MAX_DRAW);
        List<GameDTO> candidates = gameServiceClient.getRandomFromCache(draw, platforms, bearerToken);

        // game-service already narrows the draw; checking here keeps the hand on these platforms
        // even when it does not.
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(g -> g.getIgdbId() != null)
                .filter(g -> !owned.contains(g.getIgdbId()))
                .filter(g -> matchesAnyPlatform(g, platforms))
                .limit(limit)
                .map(this::toDTO)
                .toList();
    }

    // MINE never leaves the profile: a pick outside it is ignored, and with no pick left the whole
    // profile applies. ALL takes the picks as given. Empty means every platform, which is also MINE's
    // answer for a profile without platforms.
    private static Set<String> platformsToDeal(boolean allPlatforms, Set<String> picks, Set<String> profile) {
        if (allPlatforms || profile.isEmpty()) return picks;
        Set<String> onProfile = picks.stream().filter(profile::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return onProfile.isEmpty() ? profile : onProfile;
    }

    private static Set<String> cleaned(List<String> picked) {
        if (picked == null) return Set.of();
        return picked.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean matchesAnyPlatform(GameDTO game, Set<String> platforms) {
        if (platforms.isEmpty()) return true;
        if (game.getPlatforms() == null) return false;
        return game.getPlatforms().stream().anyMatch(platforms::contains);
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
