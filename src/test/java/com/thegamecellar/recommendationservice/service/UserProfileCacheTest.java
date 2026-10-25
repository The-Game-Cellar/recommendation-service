package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.support.RecommendationServiceTestBase;
import com.thegamecellar.recommendationservice.worker.UserComputeProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The profile cache on a real Redis, seen through the grouped endpoint whose row order the profile
// decides: a cached profile is served without the database, and a compute that writes a new profile
// drops the cached one once its transaction commits.
class UserProfileCacheTest extends RecommendationServiceTestBase {

    private static final String GROUPED = "/api/v1/recommendations/personalized/grouped";
    private static final String KEY = "userProfile:" + ALICE;

    @Autowired private UserProfileCache profileCache;
    @Autowired private UserComputeProcessor processor;
    @Autowired private ComputeEnqueuer enqueuer;
    @Autowired private TransactionTemplate tx;

    @BeforeEach
    void aPoolInTwoGenres() {
        for (int i = 0; i < 3; i++) {
            poolRow(ALICE, 1001 + i, "rpg-" + i, 1, "0.8000", LocalDateTime.now(), "RPG");
            poolRow(ALICE, 1101 + i, "shooter-" + i, 1, "0.8000", LocalDateTime.now(), "Shooter");
        }
        profile(ALICE, Map.of("RPG", 5.0, "Shooter", 2.0));
    }

    private ResultActions grouped() throws Exception {
        return mvc.perform(post(GROUPED).header(HttpHeaders.AUTHORIZATION, bearer(ALICE_TOKEN)));
    }

    private void countsInTheDatabase(String json) {
        jdbc.update("UPDATE user_profiles SET library_genre_counts = ?::jsonb WHERE user_id = ?", json, ALICE);
    }

    @Test
    void aCachedProfileIsServedWithoutReadingTheDatabaseAgain() throws Exception {
        grouped().andExpect(status().isOk()).andExpect(jsonPath("$.rows[*].genre", contains("RPG", "Shooter")));
        assertThat(REDIS_COMMANDS.exists(KEY)).isEqualTo(1);

        countsInTheDatabase("{\"Shooter\": 9.0, \"RPG\": 1.0}");
        grouped().andExpect(jsonPath("$.rows[*].genre", contains("RPG", "Shooter")));

        REDIS_COMMANDS.del(KEY);
        grouped().andExpect(jsonPath("$.rows[*].genre", contains("Shooter", "RPG")));
    }

    @Test
    void aComputeThatWritesANewProfileDropsTheCachedOne() throws Exception {
        grouped().andExpect(status().isOk());
        assertThat(REDIS_COMMANDS.exists(KEY)).isEqualTo(1);
        for (int i = 1; i <= 3; i++) owns(ALICE, i, "shooter-owned-" + i, "COMPLETED", "9", "Shooter", "PC");
        for (int i = 4; i <= 5; i++) owns(ALICE, i, "rpg-owned-" + i, "COMPLETED", "8", "RPG", "PC");
        catalogGame(7001, "candidate", "Shooter", "PC");
        randomQuality(7001);
        popular(7001);

        enqueuer.enqueue(ALICE);
        processor.processOneAtomicallyOrSkip();

        assertThat(REDIS_COMMANDS.exists(KEY)).isZero();
        assertThat(profileRepository.findById(ALICE).orElseThrow().getLibraryGenreCounts())
                .containsEntry("Shooter", 3.0).containsEntry("RPG", 2.0);
    }

    @Test
    void insideATransactionTheDropWaitsForTheCommit() {
        REDIS_COMMANDS.set(KEY, "{}");

        tx.executeWithoutResult(status -> {
            profileCache.invalidateAfterCommit(ALICE);
            assertThat(REDIS_COMMANDS.exists(KEY)).as("before the commit").isEqualTo(1);
        });

        assertThat(REDIS_COMMANDS.exists(KEY)).as("after the commit").isZero();
    }

    @Test
    void aRolledBackTransactionLeavesTheCachedProfile() {
        REDIS_COMMANDS.set(KEY, "{}");

        tx.executeWithoutResult(status -> {
            profileCache.invalidateAfterCommit(ALICE);
            status.setRollbackOnly();
        });

        assertThat(REDIS_COMMANDS.exists(KEY)).isEqualTo(1);
    }
}
