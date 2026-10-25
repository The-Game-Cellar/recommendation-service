package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.support.RecommendationServiceTestBase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// The other end of library-service's write signal: a user id published on the real library-write
// channel queues a full recompute for that user and drops their cached profile.
class LibraryWriteChannelTest extends RecommendationServiceTestBase {

    @Test
    void aLibraryWriteQueuesAFullRecomputeAndDropsTheCachedProfile() throws Exception {
        REDIS_COMMANDS.set("userProfile:" + ALICE, "{}");
        REDIS_COMMANDS.set("userProfile:" + BOB, "{}");

        // Published until it lands: the listener subscribes asynchronously once the context is up, and a
        // message sent before that is gone. A repeat is harmless, since the queue upsert is idempotent.
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (queueRepository.findById(ALICE).isEmpty() && Instant.now().isBefore(deadline)) {
            REDIS_COMMANDS.publish(LibraryWriteSubscriber.CHANNEL, ALICE);
            Thread.sleep(100);
        }
        assertThat(queueRepository.findById(ALICE)).as("queued after the write").isPresent();
        assertThat(queueRepository.findById(ALICE).orElseThrow().getTargetGenre()).isNull();
        while (REDIS_COMMANDS.exists("userProfile:" + ALICE) > 0 && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertThat(REDIS_COMMANDS.exists("userProfile:" + ALICE)).isZero();
        assertThat(REDIS_COMMANDS.exists("userProfile:" + BOB)).isEqualTo(1);
        assertThat(queueRepository.findById(BOB)).isEmpty();
    }
}
