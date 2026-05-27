package com.thegamecellar.recommendationservice.repository;

import com.thegamecellar.recommendationservice.model.entity.ComputeQueueEntry;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePoolId;
import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// Integration test against a real Postgres container running the Flyway V1 schema. Verifies
// insert/query/delete cycles on every table, JSONB Map roundtrip on user_profiles, and that
// the queue dequeue skips rows already locked by a concurrent transaction.
//
// disabledWithoutDocker: when Docker is unavailable the test is skipped rather than failing.
// On Windows with Docker Desktop, Testcontainers' default NpipeSocketClientProviderStrategy
// targets `\\.\pipe\docker_engine`. Some Docker Desktop installs only publish
// `\\.\pipe\dockerDesktopLinuxEngine`. Workaround: create `%USERPROFILE%\.testcontainers.properties`
// with one line `docker.host=npipe:////./pipe/dockerDesktopLinuxEngine`, or enable
// `Expose daemon on tcp://localhost:2375 without TLS` in Docker Desktop General settings.
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.data.redis.host=localhost",
        "recommendation.ratelimit.distributed=false",
        "recommendation.library-write-subscriber.enabled=false"
})
class RecDbRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rec_test_db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired UserCandidatePoolRepository poolRepo;
    @Autowired UserProfileSnapshotRepository profileRepo;
    @Autowired ComputeQueueRepository queueRepo;
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
                .computedAt(now).build();
        poolRepo.save(row);
        poolRepo.flush();

        Optional<UserCandidatePool> found = poolRepo.findById(new UserCandidatePoolId("user-A", 42));
        assertThat(found).isPresent();
        assertThat(found.get().getBaseScore()).isEqualByComparingTo("0.8500");
        assertThat(found.get().getName()).isEqualTo("Test Game");
        assertThat(found.get().getGenres()).containsExactly("Action", "RPG");
        assertThat(found.get().getPlatforms()).containsExactly("PC");

        int deleted = poolRepo.deleteByUserId("user-A");
        poolRepo.flush();
        assertThat(deleted).isEqualTo(1);
        assertThat(poolRepo.existsByUserId("user-A")).isFalse();
    }

    @Test
    @Transactional
    void userCandidatePool_findByUserIdReturnsAllRows() {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 1; i <= 5; i++) {
            poolRepo.save(UserCandidatePool.builder()
                    .userId("u").igdbId(i)
                    .baseScore(new BigDecimal("0.5000")).tier((short) 1)
                    .name("g" + i).rating(new BigDecimal("7.50"))
                    .genres(List.of("Action")).platforms(List.of("PC"))
                    .computedAt(now).build());
        }
        poolRepo.flush();

        List<UserCandidatePool> rows = poolRepo.findByUserId("u");
        assertThat(rows).hasSize(5);
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
        profileRepo.save(snap);
        profileRepo.flush();

        Optional<UserProfileSnapshot> reloaded = profileRepo.findById("user-B");
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getGenreWeights())
                .containsEntry("Action", 0.4)
                .containsEntry("RPG", 0.6);
        assertThat(reloaded.get().getPlatformWeights()).containsEntry("PC", 1.0);
        assertThat(reloaded.get().getDecadeWeights()).containsEntry("2020s", 1.0);
        assertThat(reloaded.get().getRatedCount()).isEqualTo(7);

        profileRepo.deleteById("user-B");
        profileRepo.flush();
        assertThat(profileRepo.findById("user-B")).isEmpty();
    }

    @Test
    @Transactional
    void computeQueue_upsertReplacesQueuedAt() {
        LocalDateTime first = LocalDateTime.now().minusMinutes(10);
        queueRepo.upsert("user-C", first);
        queueRepo.flush();

        Optional<ComputeQueueEntry> initial = queueRepo.findById("user-C");
        assertThat(initial).isPresent();
        assertThat(initial.get().getAttempts()).isEqualTo((short) 0);

        LocalDateTime later = LocalDateTime.now();
        queueRepo.upsert("user-C", later);
        queueRepo.flush();

        assertThat(queueRepo.count()).isEqualTo(1);
        queueRepo.deleteById("user-C");
        queueRepo.flush();
        assertThat(queueRepo.findById("user-C")).isEmpty();
    }

    @Test
    @Transactional
    void computeQueue_incrementAttempts() {
        queueRepo.upsert("user-D", LocalDateTime.now());
        queueRepo.flush();

        queueRepo.incrementAttempts("user-D");
        queueRepo.incrementAttempts("user-D");
        queueRepo.flush();

        ComputeQueueEntry reloaded = queueRepo.findById("user-D").orElseThrow();
        assertThat(reloaded.getAttempts()).isEqualTo((short) 2);
    }

    // FOR UPDATE SKIP LOCKED: when one tx holds a row's lock, a concurrent tx requesting the same
    // batch must skip it and pick the next available row. Verifies Postgres-native SKIP LOCKED is
    // wired correctly via @Lock(PESSIMISTIC_WRITE) + javax.persistence.lock.timeout = -2.
    @Test
    void dequeueBatch_skipsLockedRows() throws Exception {
        txTemplate.executeWithoutResult(s -> {
            queueRepo.upsert("user-E1", LocalDateTime.now().minusSeconds(5));
            queueRepo.upsert("user-E2", LocalDateTime.now());
        });

        ExecutorService exec = Executors.newFixedThreadPool(2);
        AtomicReference<String> firstSeen = new AtomicReference<>();
        AtomicReference<String> secondSeen = new AtomicReference<>();
        java.util.concurrent.CountDownLatch firstLocked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
        try {
            CompletableFuture<Void> tx1 = CompletableFuture.runAsync(() -> txTemplate.executeWithoutResult(s -> {
                List<ComputeQueueEntry> picked = queueRepo.dequeueBatch(org.springframework.data.domain.PageRequest.of(0, 1));
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
                List<ComputeQueueEntry> picked = queueRepo.dequeueBatch(org.springframework.data.domain.PageRequest.of(0, 1));
                secondSeen.set(picked.isEmpty() ? null : picked.get(0).getUserId());
            }), exec);

            tx2.get(5, TimeUnit.SECONDS);
            releaseFirst.countDown();
            tx1.get(5, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
            txTemplate.executeWithoutResult(s -> queueRepo.deleteAll());
        }

        assertThat(firstSeen.get()).isEqualTo("user-E1");
        assertThat(secondSeen.get()).isEqualTo("user-E2");
    }
}
