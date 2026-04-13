package com.thegamecellar.recommendationservice.service;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WildCardService {

    // TODO (post-MVP): Determine max page dynamically based on totalCount from Game Service response
    //  instead of using a fixed upper bound. RAWG has ~500k games (~25 000 pages at pageSize=20)
    //  so 500 covers a reasonable slice, but a dynamic solution would be more correct.
    // TODO (post-MVP): Precision fetch — instead of fetching a full page and discarding most results,
    //  pre-generate random (page, index) pairs and fetch pageSize=1 per wildcard slot directly.
    //  Requires knowing total count per platform (links to dynamic max page TODO above).
    //  Would reduce data transfer from N_platforms*pageSize fetched to exactly limit*1 fetched.
    private static final int MAX_PAGE = 500;

    private final GameServiceClient gameServiceClient;
    private final LibraryServiceClient libraryServiceClient;

    public List<RecommendationDTO> getWildCard(String bearerToken, int limit) {
        Set<Integer> ownedGameIds = libraryServiceClient.getGames(bearerToken).stream()
                .filter(g -> g.getRawgGameId() != null)
                .map(UserGameDTO::getRawgGameId)
                .collect(Collectors.toSet());

        List<String> platformPool = libraryServiceClient.getPlatforms(bearerToken).stream()
                .filter(p -> p.getPlatformName() != null)
                .map(UserPlatformDTO::getPlatformName)
                .collect(Collectors.toList());

        /* TODO (post-MVP): Extend Game Service to accept multiple platforms in one request
            so we can reduce this to 1 call regardless of how many platforms the user has.*/
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<GameDTO> candidates = new ArrayList<>();

        if (platformPool.isEmpty()) {
            candidates.addAll(gameServiceClient.getRandomGames(null, rng.nextInt(1, MAX_PAGE + 1)));
        } else {
            for (String platform : platformPool) {
                candidates.addAll(gameServiceClient.getRandomGames(platform, rng.nextInt(1, MAX_PAGE + 1)));
            }
        }

        Collections.shuffle(candidates);

        Set<Integer> seen = new HashSet<>();
        List<RecommendationDTO> results = candidates.stream()
                .filter(g -> g != null && g.getRawgId() != null)
                .filter(g -> seen.add(g.getRawgId()))
                .filter(g -> !ownedGameIds.contains(g.getRawgId()))
                .limit(limit)
                .map(this::toDTO)
                .collect(Collectors.toList());

        log.info("WildCard: platforms={}, candidates={}, returned={}", platformPool, candidates.size(), results.size());
        return results;
    }

    private RecommendationDTO toDTO(GameDTO game) {
        return RecommendationDTO.builder()
                .rawgId(game.getRawgId())
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
