package com.thegamecellar.recommendationservice.worker;

import com.thegamecellar.recommendationservice.model.entity.ComputeQueueEntry;
import com.thegamecellar.recommendationservice.support.RecommendationServiceTestBase;
import com.thegamecellar.recommendationservice.support.StubHttpServer.StubResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// The two scheduled jobs run as the scheduler runs them, against Postgres and the stubs.
class WorkerJobsTest extends RecommendationServiceTestBase {

    @Autowired private StaleScanner staleScanner;
    @Autowired private PerUserWorker perUserWorker;

    private static String user(int n) {
        return "33333333-0000-4000-8000-00000000000" + n;
    }

    @BeforeEach
    void catalog() {
        IntStream.rangeClosed(9001, 9004).forEach(id -> catalogGame(id, "popular-" + id, "Puzzle", "PC"));
        popular(9001, 9002, 9003, 9004);
    }

    // A pool goes stale on its newest row; a user queued for one genre is widened to a full replace.
    @Test
    void theStaleScanQueuesEveryUserWhosePoolIsPastTheTtlAndNoOneElse() {
        LocalDateTime stale = LocalDateTime.now().minusHours(25);
        poolRow(ALICE, 1, "old", 1, "0.5000", stale, "RPG");
        poolRow(BOB, 1, "old", 1, "0.5000", stale, "RPG");
        poolRow(BOB, 2, "fresh", 1, "0.5000", LocalDateTime.now(), "RPG");
        poolRow(user(1), 1, "old", 1, "0.5000", stale, "RPG");
        queued(user(1), LocalDateTime.now().minusHours(2), 1, "RPG");

        staleScanner.scan();

        assertThat(queueRepository.findAll()).extracting(ComputeQueueEntry::getUserId)
                .containsExactlyInAnyOrder(ALICE, user(1));
        assertThat(queueRepository.findById(ALICE).orElseThrow().getTargetGenre()).isNull();
        assertThat(queueRepository.findById(user(1)).orElseThrow().getTargetGenre()).isNull();
    }

    @Test
    void theStaleScanWithNothingStaleQueuesNothing() {
        poolRow(ALICE, 1, "fresh", 1, "0.5000", LocalDateTime.now(), "RPG");

        staleScanner.scan();

        assertThat(queueRepository.count()).isZero();
    }

    // One tick runs its five tasks in parallel. Each locks a different queue row (SKIP LOCKED), so the
    // five oldest users are computed at the same time, once each, and the sixth waits for the next tick.
    // Without SKIP LOCKED the tasks would queue behind the first row's lock and compute one at a time.
    // The library stub answers slowly so every task is inside its transaction while the others dequeue.
    @Test
    void oneTickComputesTheFiveOldestQueuedUsersOnceEachInParallel() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        LIBRARY_SERVICE.on("GET", "/internal/library/users/", request -> {
            mostAtOnce.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return StubResponse.json(200, "[]");
        });
        LocalDateTime base = LocalDateTime.now().minusMinutes(10);
        for (int n = 1; n <= 6; n++) queued(user(n), base.plusSeconds(n), 0, null);

        perUserWorker.runBatch();

        assertThat(mostAtOnce.get()).as("computes running at once").isEqualTo(PerUserWorker.BATCH_SIZE);
        for (int n = 1; n <= 5; n++) {
            assertThat(poolRepository.findByUserId(user(n))).as("pool of " + user(n)).hasSize(4);
            assertThat(LIBRARY_SERVICE.recorded("GET", "/internal/library/users/" + user(n) + "/games"))
                    .as("library reads for " + user(n)).hasSize(1);
        }
        assertThat(queueRepository.findAll()).extracting(ComputeQueueEntry::getUserId).containsExactly(user(6));
        assertThat(poolRepository.findByUserId(user(6))).isEmpty();
    }

    @Test
    void aTickOnAnEmptyQueueComputesNothing() {
        perUserWorker.runBatch();

        assertThat(poolRepository.count()).isZero();
        assertThat(LIBRARY_SERVICE.recorded()).isEmpty();
    }
}
