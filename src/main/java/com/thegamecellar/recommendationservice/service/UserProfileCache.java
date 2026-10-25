package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import com.thegamecellar.recommendationservice.repository.UserProfileSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;

// Redis-cached read of user_profiles, which /personalized/grouped reads on every request for its row
// order. Dropped on a library write, after a compute writes a new profile, and on account purge.
@Slf4j
@Service
public class UserProfileCache {

    private static final String KEY_PREFIX = "userProfile:";

    private final UserProfileSnapshotRepository profileRepository;
    private final Duration ttl;

    @Autowired(required = false)
    private RedisTemplate<String, UserProfileSnapshot> redis;

    public UserProfileCache(UserProfileSnapshotRepository profileRepository,
                            @Value("${recommendation.cache.user-profile-ttl-seconds:600}") long ttlSeconds) {
        this.profileRepository = profileRepository;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public Optional<UserProfileSnapshot> findByUserId(String userId) {
        String key = KEY_PREFIX + userId;
        if (redis != null) {
            try {
                UserProfileSnapshot cached = redis.opsForValue().get(key);
                if (cached != null) {
                    return Optional.of(cached);
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

    // Inside a transaction the drop waits for the commit: dropped earlier, a read racing the writer
    // would load the old row and cache it again.
    public void invalidateAfterCommit(String userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidate(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidate(userId);
            }
        });
    }
}
