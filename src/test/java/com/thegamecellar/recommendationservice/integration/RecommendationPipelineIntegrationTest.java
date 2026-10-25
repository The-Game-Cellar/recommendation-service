package com.thegamecellar.recommendationservice.integration;

import com.thegamecellar.recommendationservice.model.entity.PoolHolding;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import com.thegamecellar.recommendationservice.service.ComputeEnqueuer;
import com.thegamecellar.recommendationservice.support.RecommendationServiceTestBase;
import com.thegamecellar.recommendationservice.support.StubHttpServer.RecordedRequest;
import com.thegamecellar.recommendationservice.support.StubHttpServer.StubResponse;
import com.thegamecellar.recommendationservice.worker.UserComputeProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The whole compute path against Postgres: a queued user is dequeued, the library is read from
// library-service's internal endpoints, the tier is selected, candidates are fetched from game-service
// and scored, the pool and the profile snapshot are written, and the read endpoint then serves what
// was written. Both services are stubs at their URLs, so the internal clients and their shared token run.
@TestPropertySource(properties = "recommendation.pool.size=50")
class RecommendationPipelineIntegrationTest extends RecommendationServiceTestBase {

    private static final String PERSONALIZED = "/api/v1/recommendations/personalized";
    private static final String RPG = "Role-playing (RPG)";

    @Autowired private UserComputeProcessor processor;
    @Autowired private ComputeEnqueuer enqueuer;

    @BeforeEach
    void catalog() {
        IntStream.rangeClosed(1, 8).forEach(i -> catalogGame(9000 + i, "popular-" + i, i % 2 == 0 ? "Shooter" : "Puzzle", "PC"));
        popular(IntStream.rangeClosed(9001, 9008).toArray());
        catalogGame(7001, "rpg-a", RPG, "PC");
        catalogGame(7002, "rpg-b", RPG, "PC");
        catalogGame(7003, "sport-a", "Sport", "PC");
        catalogGame(7004, "sport-b", "Sport", "PC");
        randomQuality(7001, 7002, 7003, 7004);
        for (String user : List.of(ALICE, BOB)) ownsPlatform(user, "PC");
    }

    private void computeFor(String userId) {
        enqueuer.enqueue(userId);
        processor.processOneAtomicallyOrSkip();
    }

    private void rates(String userId, int count, String genre) {
        for (int i = 1; i <= count; i++) owns(userId, i, "owned-" + i, "COMPLETED", String.valueOf(7 + (i % 3)), genre, "PC");
    }

    private List<RecordedRequest> internalLibraryCalls() {
        return LIBRARY_SERVICE.recorded("GET", "/internal/library/users/");
    }

    @Test
    void aNewcomerWithNoRatingsGetsAPopularityPoolOnTierThree() {
        computeFor(ALICE);

        List<UserCandidatePool> pool = poolRepository.findByUserId(ALICE);
        assertThat(pool).hasSize(8);
        assertThat(pool).allMatch(row -> row.getTier() == 3);
        // Popularity order is the score: the first popular game outranks the last.
        UserCandidatePool first = pool.stream().filter(r -> r.getIgdbId() == 9001).findFirst().orElseThrow();
        UserCandidatePool last = pool.stream().filter(r -> r.getIgdbId() == 9008).findFirst().orElseThrow();
        assertThat(first.getBaseScore()).isGreaterThan(last.getBaseScore());

        UserProfileSnapshot profile = profileRepository.findById(ALICE).orElseThrow();
        assertThat(profile.getRatedCount()).isZero();
        assertThat(queueRepository.findById(ALICE)).isEmpty();
    }

    // The worker has no user token, so every library read goes to the internal endpoints for the queued
    // user, carrying the shared token; game-service is asked the same way.
    @Test
    void theComputeReadsTheQueuedUsersLibraryThroughTheInternalEndpointsWithTheSharedToken() {
        computeFor(ALICE);

        assertThat(internalLibraryCalls()).isNotEmpty()
                .allSatisfy(call -> {
                    assertThat(call.path()).startsWith("/internal/library/users/" + ALICE + "/");
                    assertThat(call.header(INTERNAL_HEADER)).isEqualTo(INTERNAL_TOKEN);
                    assertThat(call.header("Authorization")).isNull();
                });
        assertThat(GAME_SERVICE.recorded("GET", "/internal/games/")).isNotEmpty()
                .allSatisfy(call -> assertThat(call.header(INTERNAL_HEADER)).isEqualTo(INTERNAL_TOKEN));
        assertThat(LIBRARY_SERVICE.recorded("GET", "/api/v1/")).isEmpty();
    }

    @Test
    void ownedGamesNeverComeBackAsCandidates() {
        owns(ALICE, 9003, "popular-3", "BACKLOG", null, "Puzzle", "PC");

        computeFor(ALICE);

        assertThat(poolRepository.findByUserId(ALICE))
                .extracting(UserCandidatePool::getIgdbId)
                .hasSize(7)
                .doesNotContain(9003);
    }

    @Test
    void aFewRatingsSelectTierTwoAndTheProfileRanksMatchingGenresFirst() {
        rates(ALICE, 2, RPG);

        computeFor(ALICE);

        List<UserCandidatePool> pool = poolRepository.findByUserId(ALICE);
        assertThat(pool).extracting(UserCandidatePool::getIgdbId).containsExactlyInAnyOrder(7001, 7002, 7003, 7004);
        assertThat(pool).allMatch(row -> row.getTier() == 2);
        BigDecimal rpg = pool.stream().filter(r -> r.getIgdbId() == 7001).findFirst().orElseThrow().getBaseScore();
        BigDecimal sport = pool.stream().filter(r -> r.getIgdbId() == 7003).findFirst().orElseThrow().getBaseScore();
        assertThat(rpg).isGreaterThan(sport);

        UserProfileSnapshot profile = profileRepository.findById(ALICE).orElseThrow();
        assertThat(profile.getRatedCount()).isEqualTo(2);
        assertThat(profile.getGenreWeights()).containsKey(RPG);
        assertThat(profile.getLibraryGenreCounts()).containsEntry(RPG, 2.0);
    }

    @Test
    void fiveRatingsSelectTierOneAndTheReadEndpointServesFromThePool() throws Exception {
        rates(ALICE, 5, RPG);

        computeFor(ALICE);

        List<UserCandidatePool> pool = poolRepository.findByUserId(ALICE);
        assertThat(pool).isNotEmpty();
        assertThat(pool).allMatch(row -> row.getTier() == 1);
        // One genre per fixture game is one shared feature, below the seed threshold, so the
        // connection is the shared genre alone; the sport rows share nothing with the profile.
        UserCandidatePool rpgRow = pool.stream().filter(r -> r.getIgdbId() == 7001).findFirst().orElseThrow();
        assertThat(rpgRow.getSeedIgdbId()).isNull();
        assertThat(rpgRow.getSharedTags()).containsExactly(RPG);
        UserCandidatePool sportRow = pool.stream().filter(r -> r.getIgdbId() == 7003).findFirst().orElseThrow();
        assertThat(sportRow.getSharedTags()).isEmpty();

        GAME_SERVICE.reset();
        LIBRARY_SERVICE.reset();
        mvc.perform(post(PERSONALIZED).header(HttpHeaders.AUTHORIZATION, bearer(ALICE_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].tier", everyItem(is(1))))
                .andExpect(jsonPath("$[*].igdbId", everyItem(in(List.of(7001, 7002, 7003, 7004)))))
                .andExpect(jsonPath("$[?(@.igdbId == 7001)].sharedTags[0]").value(RPG));
        // The pool is fresh, so the read touched neither service and queued nothing.
        assertThat(GAME_SERVICE.recorded()).isEmpty();
        assertThat(LIBRARY_SERVICE.recorded()).isEmpty();
        assertThat(queueRepository.findById(ALICE)).isEmpty();
    }

    @Test
    void recomputingReplacesThePoolRatherThanAppendingToIt() {
        computeFor(ALICE);
        assertThat(poolRepository.findByUserId(ALICE)).hasSize(8);

        // The library grew and two of the popular games are now owned, still unrated so the
        // user stays on tier three and the same popularity fetch answers.
        owns(ALICE, 9001, "popular-1", "BACKLOG", null, "Puzzle", "PC");
        owns(ALICE, 9002, "popular-2", "BACKLOG", null, "Shooter", "PC");
        computeFor(ALICE);

        assertThat(poolRepository.findByUserId(ALICE))
                .extracting(UserCandidatePool::getIgdbId)
                .hasSize(6)
                .doesNotContain(9001, 9002);
    }

    // A failed library read must not be mistaken for an empty library: the job is retried and finally
    // dropped, and the pool and profile the user already had stay as they were.
    @Test
    void aLibraryOutageRetriesThenDeadLettersWithoutTouchingThePool() {
        rates(ALICE, 5, RPG);
        computeFor(ALICE);
        List<Integer> poolBefore = poolRepository.findByUserId(ALICE).stream().map(UserCandidatePool::getIgdbId).sorted().toList();
        UserProfileSnapshot profileBefore = profileRepository.findById(ALICE).orElseThrow();

        LIBRARY_SERVICE.on("GET", "/internal/library/users/", StubResponse.json(503, "{}"));
        enqueuer.enqueue(ALICE);
        for (int attempt = 1; attempt < UserComputeProcessor.MAX_ATTEMPTS; attempt++) {
            processor.processOneAtomicallyOrSkip();
            assertThat(queueRepository.findById(ALICE)).isPresent();
            assertThat(queueRepository.findById(ALICE).orElseThrow().getAttempts()).isEqualTo((short) attempt);
        }
        processor.processOneAtomicallyOrSkip();

        assertThat(queueRepository.findById(ALICE)).isEmpty();
        assertThat(poolRepository.findByUserId(ALICE)).extracting(UserCandidatePool::getIgdbId)
                .containsExactlyInAnyOrderElementsOf(poolBefore);
        UserProfileSnapshot profileAfter = profileRepository.findById(ALICE).orElseThrow();
        assertThat(profileAfter.getRatedCount()).isEqualTo(profileBefore.getRatedCount());
        assertThat(profileAfter.getUpdatedAt()).isEqualTo(profileBefore.getUpdatedAt());
    }

    // A failed catalog read must not be mistaken for a catalog with nothing to offer: the pool the user
    // already had stays rather than being replaced by an empty one.
    @Test
    void aGameServiceOutageRetriesThenDeadLettersWithoutTouchingThePool() {
        rates(ALICE, 5, RPG);
        computeFor(ALICE);
        List<Integer> poolBefore = poolRepository.findByUserId(ALICE).stream().map(UserCandidatePool::getIgdbId).sorted().toList();
        assertThat(poolBefore).isNotEmpty();

        GAME_SERVICE.on("GET", "/internal/games/", StubResponse.json(503, "{}"));
        enqueuer.enqueue(ALICE);
        for (int attempt = 1; attempt <= UserComputeProcessor.MAX_ATTEMPTS; attempt++) {
            processor.processOneAtomicallyOrSkip();
        }

        assertThat(queueRepository.findById(ALICE)).isEmpty();
        assertThat(poolRepository.findByUserId(ALICE)).extracting(UserCandidatePool::getIgdbId)
                .containsExactlyInAnyOrderElementsOf(poolBefore);
    }

    @Test
    void aGameServiceOutageDuringAGenreTopUpKeepsTheBucketOutOfHolding() {
        rates(ALICE, 5, RPG);
        LocalDateTime earlier = LocalDateTime.now().minusMinutes(5);
        poolRow(ALICE, 7101, "rpg-7101", 1, "0.9000", earlier, RPG);
        poolRow(ALICE, 7102, "rpg-7102", 1, "0.8000", earlier, RPG);
        GAME_SERVICE.on("GET", "/internal/games/", StubResponse.json(503, "{}"));

        enqueuer.enqueueGenre(ALICE, RPG);
        processor.processOneAtomicallyOrSkip();

        assertThat(poolRepository.findByUserId(ALICE)).extracting(UserCandidatePool::getIgdbId).containsExactlyInAnyOrder(7101, 7102);
        assertThat(holdingRepository.count()).isZero();
        assertThat(queueRepository.findById(ALICE).orElseThrow().getAttempts()).isEqualTo((short) 1);
    }

    // The per-genre top-up parks the shown bucket in pool_holding, fetches the genre afresh without
    // anything still held, and leaves the other genres and the profile untouched.
    @Test
    void aGenreTopUpHoldsTheShownBucketAndFillsTheGenreWithGamesNotHeld() {
        rates(ALICE, 5, RPG);
        int[] rpgBag = IntStream.rangeClosed(7101, 7130).toArray();
        for (int id : rpgBag) catalogGame(id, "rpg-" + id, RPG, "PC");
        randomQuality(rpgBag);
        LocalDateTime earlier = LocalDateTime.now().minusMinutes(5).truncatedTo(ChronoUnit.MICROS);
        poolRow(ALICE, 7101, "rpg-7101", 1, "0.9000", earlier, RPG);
        poolRow(ALICE, 7102, "rpg-7102", 1, "0.8000", earlier, RPG);
        poolRow(ALICE, 8001, "shooter-8001", 1, "0.7000", earlier, "Shooter");
        profile(ALICE, Map.of(RPG, 5.0));
        LocalDateTime profileWritten = profileRepository.findById(ALICE).orElseThrow().getUpdatedAt();
        // Held back by an earlier top-up and not yet released.
        holding(ALICE, RPG, 7103, LocalDateTime.now().plusSeconds(60));
        holding(BOB, RPG, 7104, LocalDateTime.now().plusSeconds(60));

        enqueuer.enqueueGenre(ALICE, RPG);
        processor.processOneAtomicallyOrSkip();

        List<Integer> pool = poolRepository.findByUserId(ALICE).stream().map(UserCandidatePool::getIgdbId).toList();
        assertThat(pool).doesNotContain(7101, 7102, 7103).contains(8001, 7104, 7105);
        assertThat(holdingRepository.findIgdbIdsForUserGenre(ALICE, RPG)).containsExactlyInAnyOrder(7101, 7102, 7103);
        assertThat(holdingRepository.findAll()).filteredOn(h -> h.getUserId().equals(BOB))
                .extracting(PoolHolding::getIgdbId).containsExactly(7104);
        assertThat(profileRepository.findById(ALICE).orElseThrow().getUpdatedAt()).isEqualTo(profileWritten);
        assertThat(queueRepository.findById(ALICE)).isEmpty();
    }

    // Before the worker has ever run for a user, the read endpoint answers live from popularity
    // through the user-facing clients, forwarding the caller's token, and queues the real compute.
    @Test
    void anEmptyPoolOnReadFallsBackToLivePopularityAndQueuesTheCompute() throws Exception {
        owns(ALICE, 9003, "popular-3", "BACKLOG", null, "Puzzle", "PC");

        mvc.perform(post(PERSONALIZED).header(HttpHeaders.AUTHORIZATION, bearer(ALICE_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[*].tier", everyItem(is(3))))
                .andExpect(jsonPath("$[*].reason", hasItem("Popular on your platforms")))
                .andExpect(jsonPath("$[*].igdbId", not(hasItem(9003))));

        assertThat(LIBRARY_SERVICE.recorded("GET", "/api/v1/library/")).isNotEmpty()
                .allSatisfy(call -> assertThat(call.header("Authorization")).isEqualTo(bearer(ALICE_TOKEN)));
        assertThat(GAME_SERVICE.recorded("GET", "/api/v1/games/popular")).isNotEmpty()
                .allSatisfy(call -> assertThat(call.query()).isEqualTo("platform=PC"));
        assertThat(queueRepository.findById(ALICE)).isPresent();
        assertThat(poolRepository.findByUserId(ALICE)).isEmpty();
    }
}
