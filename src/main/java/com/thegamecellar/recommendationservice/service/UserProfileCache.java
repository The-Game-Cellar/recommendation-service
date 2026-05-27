package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import com.thegamecellar.recommendationservice.repository.UserProfileSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

// Redis-cached read of user_profiles. UserProfileSnapshot is read on every /personalized
// /personalized/grouped request to derive row order and other user-state hints; avoiding
// the DB hop makes the warm path purely pool-read + in-mem scoring. Invalidated by the
// library-write subscriber path (Phase 5) so user-rated changes propagate immediately.
@Slf4j
@Service
public class UserProfileCache {

    private static final String KEY_PREFIX = "userProfile:";

    private final UserProfileSnapshotRepository profileRepository;
    private final Duration ttl;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redis;

    public UserProfileCache(UserProfileSnapshotRepository profileRepository,
                            @Value("${recommendation.cache.user-profile-ttl-seconds:600}") long ttlSeconds) {
        this.profileRepository = profileRepository;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public Optional<UserProfileSnapshot> findByUserId(String userId) {
        String key = KEY_PREFIX + userId;
        if (redis != null) {
            try {
                Object cached = redis.opsForValue().get(key);
                if (cached instanceof UserProfileSnapshot snap) {
                    return Optional.of(snap);
                }
            } catch (RuntimeException ex) {
                log.warn("Redis read failed for {}: {}", key, ex.getClass().getSimpleName());
            }
        }

        Optional<UserProfileSnapshot> loaded = profileRepository.findById(userId);
        if (loaded.isPresent() && redis != null) {
            try {
                redis.opsForValue().set(key, loaded.get(), ttl);
            } catch (RuntimeException ex) {
                log.warn("Redis write failed for {}: {}", key, ex.getClass().getSimpleName());
            }
        }
        return loaded;
    }

    public void invalidate(String userId) {
        if (redis == null) return;
        try {
            redis.delete(KEY_PREFIX + userId);
        } catch (RuntimeException ex) {
            log.warn("Redis invalidate failed for {}: {}", userId, ex.getClass().getSimpleName());
        }
    }
}
