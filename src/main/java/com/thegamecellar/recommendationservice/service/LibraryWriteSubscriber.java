package com.thegamecellar.recommendationservice.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import java.nio.charset.StandardCharsets;

// Listens on the Redis "library-write" channel. Each message is the changed user_id (raw
// string, no envelope). We translate to a compute_queue full-replace upsert so the worker
// rebuilds the user's pool on the next tick. Best-effort: a Redis outage leaves the user
// dependent on the stale TTL safety-net (Phase 6) or the 50% depletion trigger.
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "recommendation.library-write-subscriber.enabled", havingValue = "true", matchIfMissing = true)
public class LibraryWriteSubscriber {

    public static final String CHANNEL = "library-write";

    private final ComputeEnqueuer computeEnqueuer;
    private final UserProfileCache profileCache;

    // Optional so the service still boots when Redis is unreachable at startup; the listener
    // container reconnects automatically when Redis comes back online.
    @Autowired(required = false)
    private RedisConnectionFactory redisConnectionFactory;

    @PostConstruct
    void warn() {
        if (redisConnectionFactory == null) {
            log.warn("library-write subscriber disabled: no Redis connection factory");
        }
    }

    @Bean
    public RedisMessageListenerContainer libraryWriteListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        if (redisConnectionFactory == null) return container;
        container.setConnectionFactory(redisConnectionFactory);

        MessageListener listener = (Message message, byte[] pattern) -> {
            String userId = new String(message.getBody(), StandardCharsets.UTF_8).trim();
            if (userId.isEmpty()) return;
            log.debug("library-write event for {}", userId);
            try {
                computeEnqueuer.enqueue(userId);
                profileCache.invalidate(userId);
            } catch (RuntimeException ex) {
                log.warn("Failed to handle library-write event for {}: {}",
                        userId, ex.getClass().getSimpleName());
            }
        };
        container.addMessageListener(listener, new PatternTopic(CHANNEL));
        return container;
    }
}
