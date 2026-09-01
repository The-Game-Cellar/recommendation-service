package com.thegamecellar.recommendationservice.integration;

import com.thegamecellar.recommendationservice.client.GameServiceClient;
import com.thegamecellar.recommendationservice.client.InternalGameClient;
import com.thegamecellar.recommendationservice.client.InternalLibraryClient;
import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import com.thegamecellar.recommendationservice.repository.ComputeQueueRepository;
import com.thegamecellar.recommendationservice.repository.UserCandidatePoolRepository;
import com.thegamecellar.recommendationservice.repository.UserProfileSnapshotRepository;
import com.thegamecellar.recommendationservice.service.ComputeEnqueuer;
import com.thegamecellar.recommendationservice.service.RecommendationService;
import com.thegamecellar.recommendationservice.worker.UserComputeProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

// The whole compute path against a real Postgres 17: a queued user is dequeued, the library
// is read through the internal client, the tier is selected, candidates are fetched and
// scored, the pool and the profile snapshot are written, and the read path then serves from
// what was written. Only the two HTTP clients are mocked. Runs in its own context so the
// scheduled worker cannot race the test: its first tick sees an empty queue and the next is
// an hour away.
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.data.redis.host=localhost",
        "recommendation.ratelimit.distributed=false",
        "recommendation.library-write-subscriber.enabled=false",
        "recommendation.worker.fixed-delay-ms=3600000",
        "recommendation.stale-scan.fixed-delay-ms=3600000",
        "recommendation.pool.size=50"
})
class RecommendationPipelineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rec_pipeline_db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final String NEWCOMER = "user-newcomer";
    private static final String CASUAL = "user-casual";
    private static final String VETERAN = "user-veteran";

    @MockitoBean private InternalLibraryClient libraryClient;
    @MockitoBean private InternalGameClient gameClient;
    // The user-facing clients are only reached by the cold-start fallback on the read path.
    @MockitoBean private LibraryServiceClient libraryServiceClient;
    @MockitoBean private GameServiceClient gameServiceClient;
    @MockitoBean private JwtDecoder jwtDecoder;

    @Autowired private UserComputeProcessor processor;
    @Autowired private ComputeEnqueuer enqueuer;
    @Autowired private RecommendationService recommendationService;
    @Autowired private UserCandidatePoolRepository poolRepository;
    @Autowired private UserProfileSnapshotRepository profileRepository;
    @Autowired private ComputeQueueRepository queueRepository;
    @Autowired private TransactionTemplate tx;

    private static final UserPlatformDTO PC = platform("PC");

    private static UserPlatformDTO platform(String name) {
        UserPlatformDTO p = new UserPlatformDTO();
        p.setPlatformName(name);
        p.setIsPrimary(true);
        return p;
    }

    private static UserGameDTO rated(int igdbId, String genre, int rating) {
        UserGameDTO g = new UserGameDTO();
        g.setIgdbGameId(igdbId);
        g.setGameName("owned-" + igdbId);
        g.setStatus("COMPLETED");
        g.setRating(rating);
        g.setPlatform("PC");
        g.setGenres(List.of(genre));
        g.setThemes(List.of());
        g.setTags(List.of());
        return g;
    }

    // Above the quality bar on every axis so nothing here is dropped for being obscure.
    private static GameDTO catalog(int igdbId, String name, String genre) {
        GameDTO g = new GameDTO();
        g.setIgdbId(igdbId);
        g.setName(name);
        g.setRating(new BigDecimal("8.50"));
        g.setTotalRating(new BigDecimal("8.50"));
        g.setTotalRatingCount(500);
        g.setBackgroundImage(name + ".jpg");
        g.setReleased("2021-05-05");
        g.setGenres(List.of(genre));
        g.setPlatforms(List.of("PC"));
        g.setThemes(List.of());
        g.setTags(List.of());
        g.setSimilarGameIds(List.of());
        return g;
    }

    @BeforeEach
    void catalogAnswers() {
        when(gameClient.getPopularGames(any())).thenReturn(IntStream.rangeClosed(1, 8)
                .mapToObj(i -> catalog(9000 + i, "popular-" + i, i % 2 == 0 ? "Shooter" : "Puzzle"))
                .toList());
        // Genre fetches answer with the same mixed bag whatever genre is asked for, so the
        // profile is the only thing that can separate RPG candidates from the rest.
        when(gameClient.randomQualityByGenre(anyString(), any(), anyInt(), anyInt())).thenReturn(List.of(
                catalog(7001, "rpg-a", "Role-playing (RPG)"),
                catalog(7002, "rpg-b", "Role-playing (RPG)"),
                catalog(7003, "sport-a", "Sport"),
                catalog(7004, "sport-b", "Sport")));
        when(gameClient.getGameById(anyInt())).thenReturn(null);
        when(libraryClient.getGenrePreferences(anyString())).thenReturn(List.of());
        when(libraryClient.getTagPreferences(anyString())).thenReturn(List.of());
        when(libraryClient.getReleaseYearPreferences(anyString())).thenReturn(List.of());
        when(libraryClient.getPlatforms(anyString())).thenReturn(List.of(PC));
    }

    @AfterEach
    void wipe() {
        tx.executeWithoutResult(s -> {
            poolRepository.deleteAll();
            profileRepository.deleteAll();
            queueRepository.deleteAll();
        });
    }

    private void computeFor(String userId) {
        enqueuer.enqueue(userId);
        processor.processOneAtomicallyOrSkip();
    }

    @Test
    void aNewcomerWithNoRatingsGetsAPopularityPoolOnTierThree() {
        when(libraryClient.getGames(NEWCOMER)).thenReturn(List.of());

        computeFor(NEWCOMER);

        List<UserCandidatePool> pool = poolRepository.findByUserId(NEWCOMER);
        assertThat(pool).hasSize(8);
        assertThat(pool).allMatch(row -> row.getTier() == 3);
        // Popularity order is the score: the first popular game outranks the last.
        UserCandidatePool first = pool.stream().filter(r -> r.getIgdbId() == 9001).findFirst().orElseThrow();
        UserCandidatePool last = pool.stream().filter(r -> r.getIgdbId() == 9008).findFirst().orElseThrow();
        assertThat(first.getBaseScore()).isGreaterThan(last.getBaseScore());

        UserProfileSnapshot profile = profileRepository.findById(NEWCOMER).orElseThrow();
        assertThat(profile.getRatedCount()).isZero();
        assertThat(queueRepository.findById(NEWCOMER)).isEmpty();
    }

    @Test
    void ownedGamesNeverComeBackAsCandidates() {
        UserGameDTO ownsAPopularOne = rated(9003, "Puzzle", 8);
        ownsAPopularOne.setRating(null);
        when(libraryClient.getGames(NEWCOMER)).thenReturn(List.of(ownsAPopularOne));

        computeFor(NEWCOMER);

        assertThat(poolRepository.findByUserId(NEWCOMER))
                .extracting(UserCandidatePool::getIgdbId)
                .hasSize(7)
                .doesNotContain(9003);
    }

    @Test
    void aFewRatingsSelectTierTwoAndTheProfileRanksMatchingGenresFirst() {
        when(libraryClient.getGames(CASUAL)).thenReturn(List.of(
                rated(1, "Role-playing (RPG)", 9),
                rated(2, "Role-playing (RPG)", 8)));

        computeFor(CASUAL);

        List<UserCandidatePool> pool = poolRepository.findByUserId(CASUAL);
        assertThat(pool).extracting(UserCandidatePool::getIgdbId).containsExactlyInAnyOrder(7001, 7002, 7003, 7004);
        assertThat(pool).allMatch(row -> row.getTier() == 2);
        BigDecimal rpg = pool.stream().filter(r -> r.getIgdbId() == 7001).findFirst().orElseThrow().getBaseScore();
        BigDecimal sport = pool.stream().filter(r -> r.getIgdbId() == 7003).findFirst().orElseThrow().getBaseScore();
        assertThat(rpg).isGreaterThan(sport);

        UserProfileSnapshot profile = profileRepository.findById(CASUAL).orElseThrow();
        assertThat(profile.getRatedCount()).isEqualTo(2);
        assertThat(profile.getGenreWeights()).containsKey("Role-playing (RPG)");
        assertThat(profile.getLibraryGenreCounts()).containsEntry("Role-playing (RPG)", 2.0);
    }

    @Test
    void fiveRatingsSelectTierOneAndTheReadPathServesFromThePool() {
        when(libraryClient.getGames(VETERAN)).thenReturn(IntStream.rangeClosed(1, 5)
                .mapToObj(i -> rated(i, "Role-playing (RPG)", 7 + (i % 3)))
                .toList());

        computeFor(VETERAN);

        List<UserCandidatePool> pool = poolRepository.findByUserId(VETERAN);
        assertThat(pool).isNotEmpty();
        assertThat(pool).allMatch(row -> row.getTier() == 1);
        // One genre per fixture game is one shared feature, below the seed threshold, so the
        // connection is the shared genre alone; the sport rows share nothing with the profile.
        UserCandidatePool rpgRow = pool.stream().filter(r -> r.getIgdbId() == 7001).findFirst().orElseThrow();
        assertThat(rpgRow.getSeedIgdbId()).isNull();
        assertThat(rpgRow.getSharedTags()).containsExactly("Role-playing (RPG)");
        UserCandidatePool sportRow = pool.stream().filter(r -> r.getIgdbId() == 7003).findFirst().orElseThrow();
        assertThat(sportRow.getSharedTags()).isEmpty();

        List<RecommendationDTO> served = recommendationService.getPersonalized(VETERAN, "unused", 10, Set.of());
        assertThat(served).isNotEmpty();
        assertThat(served).allMatch(dto -> dto.getTier() == 1);
        assertThat(served).extracting(RecommendationDTO::getIgdbId).isSubsetOf(7001, 7002, 7003, 7004);
        assertThat(served).filteredOn(dto -> dto.getIgdbId() == 7001)
                .allSatisfy(dto -> assertThat(dto.getSharedTags()).containsExactly("Role-playing (RPG)"));
        // Nothing was re-enqueued: the pool is fresh, so the read was pool-only.
        assertThat(queueRepository.findById(VETERAN)).isEmpty();
    }

    @Test
    void recomputingReplacesThePoolRatherThanAppendingToIt() {
        when(libraryClient.getGames(NEWCOMER)).thenReturn(List.of());
        computeFor(NEWCOMER);
        assertThat(poolRepository.findByUserId(NEWCOMER)).hasSize(8);

        // The library grew and two of the popular games are now owned, still unrated so the
        // user stays on tier three and the same popularity fetch answers.
        UserGameDTO ownedA = rated(9001, "Puzzle", 5);
        UserGameDTO ownedB = rated(9002, "Shooter", 5);
        ownedA.setRating(null);
        ownedB.setRating(null);
        when(libraryClient.getGames(NEWCOMER)).thenReturn(List.of(ownedA, ownedB));
        computeFor(NEWCOMER);

        assertThat(poolRepository.findByUserId(NEWCOMER))
                .extracting(UserCandidatePool::getIgdbId)
                .hasSize(6)
                .doesNotContain(9001, 9002);
    }

    @Test
    void aLibraryOutageRetriesThenDeadLettersWithoutTouchingThePool() {
        when(libraryClient.getGames(CASUAL)).thenThrow(new IllegalStateException("library-service down"));
        enqueuer.enqueue(CASUAL);

        for (int attempt = 1; attempt < UserComputeProcessor.MAX_ATTEMPTS; attempt++) {
            processor.processOneAtomicallyOrSkip();
            assertThat(queueRepository.findById(CASUAL)).isPresent();
            assertThat(queueRepository.findById(CASUAL).orElseThrow().getAttempts()).isEqualTo((short) attempt);
        }
        processor.processOneAtomicallyOrSkip();

        assertThat(queueRepository.findById(CASUAL)).isEmpty();
        assertThat(poolRepository.findByUserId(CASUAL)).isEmpty();
        assertThat(profileRepository.findById(CASUAL)).isEmpty();
    }

    // Before the worker has ever run for a user, the read path answers live from popularity
    // through the user-facing clients and queues the real compute for next time.
    @Test
    void anEmptyPoolOnReadFallsBackToLivePopularityAndQueuesTheCompute() {
        when(libraryServiceClient.getPlatforms(anyString())).thenReturn(List.of(PC));
        when(libraryServiceClient.getGames(anyString())).thenReturn(List.of());
        when(gameServiceClient.getPopularGames(any(), anyString())).thenReturn(List.of(
                catalog(9101, "live-popular-a", "Puzzle"), catalog(9102, "live-popular-b", "Shooter")));

        List<RecommendationDTO> served = recommendationService.getPersonalized("user-unknown", "unused", 5, Set.of());

        assertThat(served).extracting(RecommendationDTO::getIgdbId).containsExactlyInAnyOrder(9101, 9102);
        assertThat(served).allMatch(dto -> dto.getTier() == 3);
        assertThat(queueRepository.findById("user-unknown")).isPresent();
        assertThat(poolRepository.findByUserId("user-unknown")).isEmpty();
    }
}
