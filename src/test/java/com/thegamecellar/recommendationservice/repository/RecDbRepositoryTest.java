package com.thegamecellar.recommendationservice.repository;

import com.thegamecellar.recommendationservice.model.entity.ComputeQueueEntry;
import com.thegamecellar.recommendationservice.model.entity.PoolHolding;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePoolId;
import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import com.thegamecellar.recommendationservice.service.AccountPurgeService;
import com.thegamecellar.recommendationservice.support.RecommendationServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// The repositories against Postgres running the real migrations: insert, query and delete on every
// table, the JSONB map round trip, the queue's upserts and SKIP LOCKED dequeue, and the purge.
class RecDbRepositoryTest extends RecommendationServiceTestBase {

    @Autowired AccountPurgeService accountPurgeService;
    @Autowired TransactionTemplate txTemplate;

    @Test
    @Transactional
    void userCandidatePool_insertQueryDeleteCycle() {
        LocalDateTime now = LocalDateTime.now();
        UserCandidatePool row = UserCandidatePool.builder()
                .userId("user-A").igdbId(42).baseScore(new BigDecimal("0.8500"))
                .tier((short) 1).name("Test Game").backgroundImage("cover.jpg")
                .rating(new BigDecimal("8.50"))
                .genres(List.of("Action", "RPG")).platforms(List.of("PC"))
                .seedIgdbId(7).seedName("Seed").seedRating(new BigDecimal("9.5")).sharedTags(List.of("RPG", "Fantasy"))
                .computedAt(now).build();
        poolRepository.save(row);
        poolRepository.flush();

        Optional<UserCandidatePool> found = poolRepository.findById(new UserCandidatePoolId("user-A", 42));
        assertThat(found).isPresent();
        assertThat(found.get().getBaseScore()).isEqualByComparingTo("0.8500");
        assertThat(found.get().getName()).isEqualTo("Test Game");
        assertThat(found.get().getGenres()).containsExactly("Action", "RPG");
        assertThat(found.get().getPlatforms()).containsExactly("PC");
        assertThat(found.get().getSeedIgdbId()).isEqualTo(7);
        assertThat(found.get().getSeedName()).isEqualTo("Seed");
        assertThat(found.get().getSeedRating()).isEqualByComparingTo("9.5");
        assertThat(found.get().getSharedTags()).containsExactly("RPG", "Fantasy");

        int deleted = poolRepository.deleteByUserId("user-A");
        poolRepository.flush();
        assertThat(deleted).isEqualTo(1);
        assertThat(poolRepository.existsByUserId("user-A")).isFalse();
    }

    @Test
    @Transactional
    void userCandidatePool_findByUserIdReturnsAllRows() {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 1; i <= 5; i++) {
            poolRepository.save(poolRow("u", i, now));
        }
        poolRepository.save(poolRow("someone-else", 1, now));
        poolRepository.flush();

        List<UserCandidatePool> rows = poolRepository.findByUserId("u");
        assertThat(rows).hasSize(5).allMatch(r -> r.getUserId().equals("u"));
    }

    // A user is stale only once the newest of their rows is past the cutoff: one fresh row among
    // old ones keeps the whole pool fresh.
    @Test
    void userCandidatePool_findStaleUserIdsTakesTheNewestRowPerUser() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        txTemplate.executeWithoutResult(s -> {
            poolRepository.save(poolRow("all-old", 1, cutoff.minusHours(2)));
            poolRepository.save(poolRow("all-old", 2, cutoff.minusMinutes(1)));
            poolRepository.save(poolRow("one-fresh", 1, cutoff.minusHours(5)));
            poolRepository.save(poolRow("one-fresh", 2, cutoff.plusMinutes(1)));
            poolRepository.save(poolRow("all-fresh", 1, cutoff.plusHours(3)));
        });

        assertThat(poolRepository.findStaleUserIds(cutoff)).containsExactly("all-old");
    }

    @Test
    @Transactional
    void userProfiles_jsonbMapRoundTrip() {
        Map<String, Double> genres = new HashMap<>();
        genres.put("Action", 0.4);
        genres.put("RPG", 0.6);
        Map<String, Double> platforms = Map.of("PC", 1.0);

        UserProfileSnapshot snap = UserProfileSnapshot.builder()
                .userId("user-B")
                .genreWeights(genres)
                .tagWeights(new HashMap<>())
                .platformWeights(platforms)
                .decadeWeights(Map.of("2020s", 1.0))
                .ratedCount(7)
                .updatedAt(LocalDateTime.now())
                .build();
        profileRepository.save(snap);
        profileRepository.flush();

        Optional<UserProfileSnapshot> reloaded = profileRepository.findById("user-B");
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getGenreWeights())
                .containsEntry("Action", 0.4)
                .containsEntry("RPG", 0.6);
        assertThat(reloaded.get().getPlatformWeights()).containsEntry("PC", 1.0);
        assertThat(reloaded.get().getDecadeWeights()).containsEntry("2020s", 1.0);
        assertThat(reloaded.get().getRatedCount()).isEqualTo(7);

        profileRepository.deleteById("user-B");
        profileRepository.flush();
        assertThat(profileRepository.findById("user-B")).isEmpty();
    }

    // A second full-replace upsert moves the job's time forward and widens a queued per-genre top-up
    // back to a full replace, keeping its attempts.
    @Test
    void computeQueue_upsertOnAQueuedUserTakesTheNewTimeAndClearsTheGenre() {
        LocalDateTime first = LocalDateTime.now().minusMinutes(10).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime later = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        txTemplate.executeWithoutResult(s -> queued("user-C", first, 1, "RPG"));

        txTemplate.executeWithoutResult(s -> queueRepository.upsert("user-C", later));

        ComputeQueueEntry entry = queueRepository.findById("user-C").orElseThrow();
        assertThat(queueRepository.count()).isEqualTo(1);
        assertThat(entry.getQueuedAt()).isEqualTo(later);
        assertThat(entry.getTargetGenre()).isNull();
        assertThat(entry.getAttempts()).isEqualTo((short) 1);
    }

    @Test
    void computeQueue_upsertOnANewUserQueuesAFullReplaceWithNoAttempts() {
        txTemplate.executeWithoutResult(s -> queueRepository.upsert("user-new", LocalDateTime.now()));

        ComputeQueueEntry entry = queueRepository.findById("user-new").orElseThrow();
        assertThat(entry.getTargetGenre()).isNull();
        assertThat(entry.getAttempts()).isZero();
    }

    @Test
    void computeQueue_upsertGenreOnANewUserQueuesThatGenre() {
        txTemplate.executeWithoutResult(s -> queueRepository.upsertGenre("user-G", LocalDateTime.now(), "RPG"));

        ComputeQueueEntry entry = queueRepository.findById("user-G").orElseThrow();
        assertThat(entry.getTargetGenre()).isEqualTo("RPG");
        assertThat(entry.getAttempts()).isZero();
    }

    // A queued full replace covers every genre, so a per-genre upsert must not narrow it.
    @Test
    void computeQueue_upsertGenreNeverNarrowsAQueuedFullReplace() {
        LocalDateTime later = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        txTemplate.executeWithoutResult(s -> queued("user-F", later.minusMinutes(5), 0, null));

        txTemplate.executeWithoutResult(s -> queueRepository.upsertGenre("user-F", later, "RPG"));

        ComputeQueueEntry entry = queueRepository.findById("user-F").orElseThrow();
        assertThat(entry.getTargetGenre()).isNull();
        assertThat(entry.getQueuedAt()).isEqualTo(later);
    }

    @Test
    void computeQueue_upsertGenreOnAQueuedTopUpTakesTheLatestGenre() {
        txTemplate.executeWithoutResult(s -> queued("user-T", LocalDateTime.now().minusMinutes(5), 2, "RPG"));

        txTemplate.executeWithoutResult(s -> queueRepository.upsertGenre("user-T", LocalDateTime.now(), "Shooter"));

        ComputeQueueEntry entry = queueRepository.findById("user-T").orElseThrow();
        assertThat(entry.getTargetGenre()).isEqualTo("Shooter");
        assertThat(entry.getAttempts()).isEqualTo((short) 2);
    }

    @Test
    @Transactional
    void computeQueue_incrementAttempts() {
        queueRepository.upsert("user-D", LocalDateTime.now());
        queueRepository.flush();

        queueRepository.incrementAttempts("user-D");
        queueRepository.incrementAttempts("user-D");
        queueRepository.flush();

        ComputeQueueEntry reloaded = queueRepository.findById("user-D").orElseThrow();
        assertThat(reloaded.getAttempts()).isEqualTo((short) 2);
    }

    // FOR UPDATE SKIP LOCKED: when one tx holds a row's lock, a concurrent tx requesting the same
    // batch must skip it and pick the next available row.
    @Test
    void dequeueBatch_skipsLockedRows() throws Exception {
        txTemplate.executeWithoutResult(s -> {
            queueRepository.upsert("user-E1", LocalDateTime.now().minusSeconds(5));
            queueRepository.upsert("user-E2", LocalDateTime.now());
        });

        ExecutorService exec = Executors.newFixedThreadPool(2);
        AtomicReference<String> firstSeen = new AtomicReference<>();
        AtomicReference<String> secondSeen = new AtomicReference<>();
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            CompletableFuture<Void> tx1 = CompletableFuture.runAsync(() -> txTemplate.executeWithoutResult(s -> {
                List<ComputeQueueEntry> picked = queueRepository.dequeueBatch(org.springframework.data.domain.PageRequest.of(0, 1));
                firstSeen.set(picked.get(0).getUserId());
                firstLocked.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }), exec);

            firstLocked.await(5, TimeUnit.SECONDS);

            CompletableFuture<Void> tx2 = CompletableFuture.runAsync(() -> txTemplate.executeWithoutResult(s -> {
                List<ComputeQueueEntry> picked = queueRepository.dequeueBatch(org.springframework.data.domain.PageRequest.of(0, 1));
                secondSeen.set(picked.isEmpty() ? null : picked.get(0).getUserId());
            }), exec);

            tx2.get(5, TimeUnit.SECONDS);
            releaseFirst.countDown();
            tx1.get(5, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
        }

        assertThat(firstSeen.get()).isEqualTo("user-E1");
        assertThat(secondSeen.get()).isEqualTo("user-E2");
    }

    // Strictly before the cutoff goes, whoever it belongs to; the rest stays.
    @Test
    void poolHolding_deleteExpiredRemovesOnlyRowsReleasedBeforeTheCutoff() {
        LocalDateTime cutoff = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        txTemplate.executeWithoutResult(s -> {
            holding("user-H", "RPG", 1, cutoff.minusSeconds(30));
            holding("user-I", "Shooter", 2, cutoff.minusSeconds(1));
            holding("user-H", "RPG", 3, cutoff);
            holding("user-I", "Shooter", 4, cutoff.plusSeconds(60));
        });

        int removed = txTemplate.execute(s -> holdingRepository.deleteExpired(cutoff));

        assertThat(removed).isEqualTo(2);
        assertThat(holdingRepository.findAll()).extracting(PoolHolding::getIgdbId).containsExactlyInAnyOrder(3, 4);
    }

    @Test
    void poolHolding_findIgdbIdsForUserGenreKeepsToThatUserAndGenre() {
        LocalDateTime now = LocalDateTime.now();
        txTemplate.executeWithoutResult(s -> {
            holding("user-H", "RPG", 1, now);
            holding("user-H", "RPG", 2, now);
            holding("user-H", "Shooter", 3, now);
            holding("user-I", "RPG", 4, now);
        });

        assertThat(holdingRepository.findIgdbIdsForUserGenre("user-H", "RPG")).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void accountPurge_removesEveryRowForTheUserAndNoOtherUsers() {
        txTemplate.executeWithoutResult(s -> {
            for (String user : List.of("purge-me", "keep-me")) {
                poolRepository.save(poolRow(user, 1, LocalDateTime.now()));
                profileRepository.save(profile(user));
                queueRepository.upsert(user, LocalDateTime.now());
                holdingRepository.save(PoolHolding.builder()
                        .userId(user).genre("RPG").igdbId(1).releasedAt(LocalDateTime.now()).build());
            }
        });

        AccountPurgeService.PurgeResult result = accountPurgeService.purgeUser("purge-me");

        assertThat(result).isEqualTo(new AccountPurgeService.PurgeResult(1, 1, 1, 1));
        assertThat(poolRepository.existsByUserId("purge-me")).isFalse();
        assertThat(profileRepository.findById("purge-me")).isEmpty();
        assertThat(queueRepository.findById("purge-me")).isEmpty();
        assertThat(holdingRepository.findIgdbIdsForUserGenre("purge-me", "RPG")).isEmpty();

        assertThat(poolRepository.existsByUserId("keep-me")).isTrue();
        assertThat(profileRepository.findById("keep-me")).isPresent();
        assertThat(queueRepository.findById("keep-me")).isPresent();
        assertThat(holdingRepository.findIgdbIdsForUserGenre("keep-me", "RPG")).containsExactly(1);
    }

    // A compute that dequeued the user before the purge holds the queue row's lock and writes
    // its results on commit. The purge must wait for that commit, then remove what it wrote;
    // otherwise the rows outlive the account and the stale scanner recomputes them forever.
    @Test
    void accountPurge_waitsForAComputeInFlightAndRemovesWhatItWrote() throws Exception {
        txTemplate.executeWithoutResult(s -> queueRepository.upsert("in-flight", LocalDateTime.now()));

        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch dequeued = new CountDownLatch(1);
        CountDownLatch finishCompute = new CountDownLatch(1);
        try {
            CompletableFuture<Void> compute = CompletableFuture.runAsync(() -> txTemplate.executeWithoutResult(s -> {
                queueRepository.dequeueBatch(org.springframework.data.domain.PageRequest.of(0, 1));
                dequeued.countDown();
                try {
                    finishCompute.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                poolRepository.save(poolRow("in-flight", 1, LocalDateTime.now()));
                profileRepository.save(profile("in-flight"));
                queueRepository.deleteById("in-flight");
            }), exec);
            assertThat(dequeued.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<AccountPurgeService.PurgeResult> purge =
                    CompletableFuture.supplyAsync(() -> accountPurgeService.purgeUser("in-flight"), exec);
            Thread.sleep(500);
            assertThat(purge).isNotDone();

            finishCompute.countDown();
            compute.get(5, TimeUnit.SECONDS);
            AccountPurgeService.PurgeResult result = purge.get(5, TimeUnit.SECONDS);

            assertThat(result.queueRemoved()).isZero();
            assertThat(result.poolRemoved()).isEqualTo(1);
            assertThat(result.profileRemoved()).isEqualTo(1);
            assertThat(poolRepository.existsByUserId("in-flight")).isFalse();
            assertThat(profileRepository.findById("in-flight")).isEmpty();
        } finally {
            exec.shutdownNow();
        }
    }

    private static UserCandidatePool poolRow(String userId, int igdbId, LocalDateTime computedAt) {
        return UserCandidatePool.builder()
                .userId(userId).igdbId(igdbId).baseScore(new BigDecimal("0.5000")).tier((short) 1)
                .name("g" + igdbId).rating(new BigDecimal("7.50"))
                .genres(List.of("RPG")).platforms(List.of("PC"))
                .computedAt(computedAt).build();
    }

    private static UserProfileSnapshot profile(String userId) {
        return UserProfileSnapshot.builder()
                .userId(userId)
                .genreWeights(Map.of("RPG", 1.0))
                .tagWeights(Map.of())
                .platformWeights(Map.of("PC", 1.0))
                .decadeWeights(Map.of())
                .ratedCount(1)
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
