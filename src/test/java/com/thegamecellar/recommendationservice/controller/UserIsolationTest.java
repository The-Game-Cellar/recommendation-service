package com.thegamecellar.recommendationservice.controller;

import com.thegamecellar.recommendationservice.support.RecommendationServiceTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Every user-keyed endpoint acts on the user in the token. Alice and Bob each have a pool, a profile,
// a library, platforms and cached library state; with Alice's token every answer is built from
// Alice's alone, only Alice's token reaches library-service, and nothing of Bob's moves.
class UserIsolationTest extends RecommendationServiceTestBase {

    private static final String BASE = "/api/v1/recommendations";
    private static final String RPG = "Role-playing (RPG)";
    private static final String STRATEGY = "Strategy";
    private static final List<String> BOBS_MARKERS = List.of("bobs-pool", STRATEGY, BOB);

    private String bobsRowsBefore;
    private Map<String, String> bobsCacheBefore;

    @BeforeEach
    void twoFullAccounts() {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 3; i++) {
            poolRow(ALICE, 1001 + i, "alices-pool-" + i, 1, "0.8000", now, RPG);
            poolRow(BOB, 2001 + i, "bobs-pool-" + i, 1, "0.9000", now, STRATEGY);
        }
        // One of Bob's rows shares Alice's genre, so a read that ignored the user would put it on her shelf.
        poolRow(BOB, 2004, "bobs-pool-rpg", 1, "0.9900", now, RPG);
        profile(ALICE, Map.of(RPG, 3.0));
        profile(BOB, Map.of(STRATEGY, 3.0));
        holding(BOB, STRATEGY, 2101, now.plusSeconds(60));
        queued(BOB, now.minusMinutes(1), 1, STRATEGY);

        ownsPlatform(ALICE, "PC");
        owns(ALICE, 1501, "Hollow Knight", "COMPLETED", "9", RPG, "PC");
        ownsPlatform(BOB, "Nintendo Switch");
        owns(BOB, 2501, "Celeste", "COMPLETED", "9", "Platform", "PC");
        // What a request read under Bob's id would find: Bob's platforms would filter every PC game away.
        REDIS_COMMANDS.set("userLibrary:" + BOB, "[2501]");
        REDIS_COMMANDS.set("userPlatforms:" + BOB, "[\"Nintendo Switch\"]");

        catalogGame(1501, "Hollow Knight", RPG, "PC");
        catalogGame(2501, "Celeste", "Platform", "PC");
        catalogGame(3001, "Tunic", "Adventure", "PC");
        catalogGame(3002, "Splatoon", "Shooter", "Nintendo Switch");
        randomGames(1501, 2501, 3001, 3002);
        similarTo(1501, 1501, 2501, 3001, 3002);

        bobsRowsBefore = bobsRows();
        bobsCacheBefore = bobsCache();
    }

    @AfterEach
    void nothingOfBobsMovedAndOnlyAlicesTokenWasForwarded() {
        assertThat(bobsRows()).isEqualTo(bobsRowsBefore);
        assertThat(bobsCache()).isEqualTo(bobsCacheBefore);
        assertThat(LIBRARY_SERVICE.recorded()).allSatisfy(call ->
                assertThat(call.header("Authorization")).isEqualTo(bearer(ALICE_TOKEN)));
    }

    // Everything of Bob's in one string, so a test can prove none of it moved.
    private String bobsRows() {
        return String.join("|", jdbc.queryForList("""
                SELECT 'pool:' || igdb_id || ':' || name || ':' || base_score || ':' || computed_at FROM user_candidate_pool WHERE user_id = ?1
                UNION ALL SELECT 'profile:' || genre_weights::text || ':' || library_genre_counts::text || ':' || updated_at FROM user_profiles WHERE user_id = ?1
                UNION ALL SELECT 'holding:' || genre || ':' || igdb_id || ':' || released_at FROM pool_holding WHERE user_id = ?1
                UNION ALL SELECT 'queue:' || queued_at || ':' || attempts || ':' || COALESCE(target_genre, '-') FROM compute_queue WHERE user_id = ?1
                ORDER BY 1
                """.replace("?1", "'" + BOB + "'"), String.class));
    }

    private Map<String, String> bobsCache() {
        return Map.of(
                "library", String.valueOf(REDIS_COMMANDS.get("userLibrary:" + BOB)),
                "platforms", String.valueOf(REDIS_COMMANDS.get("userPlatforms:" + BOB)),
                "profile", String.valueOf(REDIS_COMMANDS.get("userProfile:" + BOB)));
    }

    private ResultActions asAlice(MockHttpServletRequestBuilder request) throws Exception {
        ResultActions result = mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(ALICE_TOKEN)));
        String body = result.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(BOBS_MARKERS);
        return result;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void personalizedServesOnlyAlicesPool() throws Exception {
        asAlice(json(post(BASE + "/personalized"), "{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].igdbId", containsInAnyOrder(1001, 1002, 1003)));
    }

    // Bob's profile would put every row under Strategy, where Alice's pool has nothing.
    @Test
    void groupedBuildsTheRowsFromAlicesPoolAndProfile() throws Exception {
        asAlice(post(BASE + "/personalized/grouped"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].genre").value(RPG))
                .andExpect(jsonPath("$.rows[0].games[*].igdbId", containsInAnyOrder(1001, 1002, 1003)));
    }

    @Test
    void aGenreRowServesAlicesBucketAndQueuesTheTopUpForAliceOnly() throws Exception {
        asAlice(json(post(BASE + "/personalized/grouped/genre"), "{\"genre\":\"" + RPG + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games[*].igdbId", containsInAnyOrder(1001, 1002, 1003)));

        assertThat(queueRepository.findById(ALICE).orElseThrow().getTargetGenre()).isEqualTo(RPG);
    }

    // Handed the genre only Bob's pool has, Alice gets nothing rather than Bob's row.
    @Test
    void aGenreRowForBobsGenreAnswersNothingForAlice() throws Exception {
        asAlice(json(post(BASE + "/personalized/grouped/genre"), "{\"genre\":\"" + STRATEGY + "\"}"))
                .andExpect(status().isNoContent());
    }

    // Alice owns Hollow Knight and plays on PC; Bob owns Celeste and plays on Switch. Alice's hand keeps
    // Celeste and drops what she owns and what is off her platform.
    @Test
    void wildcardFiltersByAlicesLibraryAndPlatforms() throws Exception {
        asAlice(get(BASE + "/wildcard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].igdbId", contains(2501, 3001)));
    }

    @Test
    void becauseYouLikedFiltersByAlicesLibraryAndPlatforms() throws Exception {
        asAlice(get(BASE + "/because-you-liked/1501"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].igdbId", contains(2501, 3001)));
    }

    @Test
    void rollingBecauseYouLikedSeedsFromAlicesLibrary() throws Exception {
        asAlice(get(BASE + "/dashboard/because-you-liked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basedOnIgdbId").value(1501))
                .andExpect(jsonPath("$.recommendations[*].igdbId", contains(2501, 3001)));
    }

    // With eight or more candidates in the seed's genre the section is cut from the pool instead of the
    // catalog; it is Alice's pool, so Bob's row in her genre never joins it.
    @Test
    void rollingBecauseYouLikedFromThePoolUsesAlicesPool() throws Exception {
        for (int i = 0; i < 8; i++) poolRow(ALICE, 1011 + i, "alices-pool-more-" + i, 1, "0.7000", LocalDateTime.now(), RPG);

        asAlice(get(BASE + "/dashboard/because-you-liked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basedOnIgdbId").value(1501))
                .andExpect(jsonPath("$.recommendations.length()").value(11));
        assertThat(GAME_SERVICE.recorded("GET", "/api/v1/games/1501/similar")).isEmpty();
    }

    @Test
    void theDashboardIsAlicesInEverySection() throws Exception {
        asAlice(post(BASE + "/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations[*].igdbId", containsInAnyOrder(1001, 1002, 1003)))
                .andExpect(jsonPath("$.wildcard[*].igdbId", contains(2501, 3001)))
                .andExpect(jsonPath("$.becauseYouLiked[0].basedOnIgdbId").value(1501));
    }
}
