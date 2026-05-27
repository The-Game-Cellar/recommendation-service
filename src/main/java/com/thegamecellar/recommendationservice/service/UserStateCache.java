package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// Per-user owned-igdb-ids + active platform names cached in Redis. Consumed by wildcard and
// becauseYouLiked exclusion paths so those reads do not hit library-service every request.
// Cache misses degrade silently to a fresh fetch (no 5xx propagation); Redis-down gives the same
// result minus the cache hit. TTL kept short so library writes are reflected within the window
// even before event-driven pub/sub invalidation lands.
@Slf4j
@Service
public class UserStateCache {

    private static final String LIBRARY_KEY = "userLibrary:";
    private static final String PLATFORMS_KEY = "userPlatforms:";

    private final LibraryServiceClient libraryClient;
    private final Duration ttl;

    // Optional so a Redis outage at startup does not crash the service (the connection-factory
    // bean is created by Spring Boot auto-config regardless of reachability).
    @Autowired(required = false)
    private RedisTemplate<String, Object> redis;

    public UserStateCache(LibraryServiceClient libraryClient,
                          @Value("${recommendation.cache.user-state-ttl-seconds:300}") long ttlSeconds) {
        this.libraryClient = libraryClient;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    @SuppressWarnings("unchecked")
    public Set<Integer> getLibraryIgdbIds(String userId, String bearerToken) {
        String key = LIBRARY_KEY + userId;
        if (redis != null) {
            try {
                Object hit = redis.opsForValue().get(key);
                if (hit instanceof java.util.Collection<?> c) {
                    return c.stream()
                            .map(o -> o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o)))
                            .collect(Collectors.toUnmodifiableSet());
                }
            } catch (RuntimeException ex) {
                log.warn("Redis read failed for {} (lib): {}", key, ex.getClass().getSimpleName());
            }
        }

        List<UserGameDTO> games = libraryClient.getGames(bearerToken);
        Set<Integer> ids = games == null ? Set.of() : games.stream()
                .map(UserGameDTO::getIgdbGameId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());

        if (redis != null) {
            try {
                redis.opsForValue().set(key, ids, ttl);
            } catch (RuntimeException ex) {
                log.warn("Redis write failed for {} (lib): {}", key, ex.getClass().getSimpleName());
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    public Set<String> getPlatformNames(String userId, String bearerToken) {
        String key = PLATFORMS_KEY + userId;
        if (redis != null) {
            try {
                Object hit = redis.opsForValue().get(key);
                if (hit instanceof java.util.Collection<?> c) {
                    return c.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
                }
            } catch (RuntimeException ex) {
                log.warn("Redis read failed for {} (platforms): {}", key, ex.getClass().getSimpleName());
            }
        }

        List<UserPlatformDTO> platforms = libraryClient.getPlatforms(bearerToken);
        Set<String> names = platforms == null ? Set.of() : platforms.stream()
                .map(UserPlatformDTO::getPlatformName)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());

        if (redis != null) {
            try {
                redis.opsForValue().set(key, names, ttl);
            } catch (RuntimeException ex) {
                log.warn("Redis write failed for {} (platforms): {}", key, ex.getClass().getSimpleName());
            }
        }
        return names;
    }

    public void invalidate(String userId) {
        if (redis == null) return;
        try {
            redis.delete(java.util.List.of(LIBRARY_KEY + userId, PLATFORMS_KEY + userId));
        } catch (RuntimeException ex) {
            log.warn("Redis invalidate failed for user {}: {}", userId, ex.getClass().getSimpleName());
        }
    }
}
