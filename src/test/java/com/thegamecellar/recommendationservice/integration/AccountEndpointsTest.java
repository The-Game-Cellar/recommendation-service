package com.thegamecellar.recommendationservice.integration;

import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import com.thegamecellar.recommendationservice.support.RecommendationServiceTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The two GDPR endpoints driven the way the gateway calls them: an HTTP request with a bearer token
// through the real security chain, against Postgres running the real migrations.
class AccountEndpointsTest extends RecommendationServiceTestBase {

    private static final String EXPORT = "/api/v1/recommendations/account/export";
    private static final String ACCOUNT = "/api/v1/recommendations/account";

    @BeforeEach
    void rowsForBothUsers() {
        seed(ALICE, 1001, "Hollow Knight", "Action");
        seed(BOB, 1002, "Stardew Valley", "Simulator");
    }

    private void seed(String userId, int igdbId, String gameName, String genre) {
        LocalDateTime now = LocalDateTime.now();
        profileRepository.save(UserProfileSnapshot.builder().userId(userId)
                .genreWeights(Map.of(genre, 0.9)).tagWeights(Map.of(genre + "-tag", 0.4))
                .platformWeights(Map.of("PC", 1.0)).decadeWeights(Map.of("2010s", 0.7))
                .libraryGenreCounts(Map.of(genre, 3.0)).ratedCount(5).updatedAt(now).build());
        poolRepository.save(UserCandidatePool.builder().userId(userId).igdbId(igdbId).name(gameName)
                .baseScore(new BigDecimal("0.8500")).tier((short) 1).rating(new BigDecimal("8.50"))
                .genres(List.of(genre)).platforms(List.of("PC")).seedIgdbId(7).seedName("Seed")
                .seedRating(new BigDecimal("9.0")).sharedTags(List.of(genre)).computedAt(now).build());
        holding(userId, genre, igdbId + 1, now);
        queued(userId, now, 2, genre);
    }

    @Test
    void exportWithoutATokenOrWithARejectedOneIsRefused() throws Exception {
        mvc.perform(get(EXPORT)).andExpect(status().isUnauthorized());
        mvc.perform(get(EXPORT).header(HttpHeaders.AUTHORIZATION, bearer("forged"))).andExpect(status().isUnauthorized());
    }

    // Every row of the four tables for the caller, each column included, and nothing of Bob's.
    @Test
    void exportHoldsEveryRowOfTheCallerAndNoneOfAnotherUsers() throws Exception {
        mvc.perform(get(EXPORT).header(HttpHeaders.AUTHORIZATION, bearer(ALICE_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(ALICE))
                .andExpect(jsonPath("$.profile.genreWeights.Action").value(0.9))
                .andExpect(jsonPath("$.profile.tagWeights['Action-tag']").value(0.4))
                .andExpect(jsonPath("$.profile.platformWeights.PC").value(1.0))
                .andExpect(jsonPath("$.profile.decadeWeights['2010s']").value(0.7))
                .andExpect(jsonPath("$.profile.libraryGenreCounts.Action").value(3.0))
                .andExpect(jsonPath("$.profile.ratedCount").value(5))
                .andExpect(jsonPath("$.profile.updatedAt").value(notNullValue()))
                .andExpect(jsonPath("$.candidatePool", hasSize(1)))
                .andExpect(jsonPath("$.candidatePool[0].name").value("Hollow Knight"))
                .andExpect(jsonPath("$.candidatePool[0].baseScore").value(0.85))
                .andExpect(jsonPath("$.candidatePool[0].seedName").value("Seed"))
                .andExpect(jsonPath("$.candidatePool[0].sharedTags[0]").value("Action"))
                .andExpect(jsonPath("$.candidatePool[0].computedAt").value(notNullValue()))
                .andExpect(jsonPath("$.heldBackGames", hasSize(1)))
                .andExpect(jsonPath("$.heldBackGames[0].igdbId").value(1002))
                .andExpect(jsonPath("$.heldBackGames[0].releasedAt").value(notNullValue()))
                .andExpect(jsonPath("$.pendingRecompute.attempts").value(2))
                .andExpect(jsonPath("$.pendingRecompute.targetGenre").value("Action"))
                .andExpect(content().string(not(containsString("Stardew Valley"))))
                .andExpect(content().string(not(containsString(BOB))));
    }

    @Test
    void deleteWithoutATokenOrWithARejectedOneTouchesNothing() throws Exception {
        mvc.perform(delete(ACCOUNT)).andExpect(status().isUnauthorized());
        mvc.perform(delete(ACCOUNT).header(HttpHeaders.AUTHORIZATION, bearer("forged"))).andExpect(status().isUnauthorized());

        for (String table : TABLES) {
            assertThat(rows(table, ALICE)).as(table).isEqualTo(1);
        }
    }

    // The caller is taken from the token, so the purge empties all four tables for Alice and
    // leaves every one of Bob's rows where it was.
    @Test
    void deletePurgesEveryTableForTheCallerAndLeavesAnotherUserAlone() throws Exception {
        mvc.perform(delete(ACCOUNT).header(HttpHeaders.AUTHORIZATION, bearer(ALICE_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueRemoved").value(1))
                .andExpect(jsonPath("$.holdingRemoved").value(1))
                .andExpect(jsonPath("$.poolRemoved").value(1))
                .andExpect(jsonPath("$.profileRemoved").value(1));

        for (String table : TABLES) {
            assertThat(rows(table, ALICE)).as(table + " for the caller").isZero();
            assertThat(rows(table, BOB)).as(table + " for another user").isEqualTo(1);
        }
    }
}
