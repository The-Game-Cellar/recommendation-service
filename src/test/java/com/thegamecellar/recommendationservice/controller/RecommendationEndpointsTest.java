package com.thegamecellar.recommendationservice.controller;

import com.thegamecellar.recommendationservice.model.entity.ComputeQueueEntry;
import com.thegamecellar.recommendationservice.support.RecommendationServiceTestBase;
import com.thegamecellar.recommendationservice.support.StubHttpServer.StubResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Every recommendation endpoint driven over HTTP with a bearer token through the real security chain,
// reading the pool from Postgres and the library and catalog from the stubs: the answer when it works,
// the refusals, and the empty answers the frontend treats as "nothing to show".
class RecommendationEndpointsTest extends RecommendationServiceTestBase {

    private static final String BASE = "/api/v1/recommendations";
    private static final String PERSONALIZED = BASE + "/personalized";
    private static final String GROUPED = BASE + "/personalized/grouped";
    private static final String GENRE_ROW = BASE + "/personalized/grouped/genre";
    private static final String WILDCARD = BASE + "/wildcard";
    private static final String SIMILAR = BASE + "/similar/";
    private static final String BECAUSE_YOU_LIKED = BASE + "/because-you-liked/";
    private static final String ROLL_BECAUSE_YOU_LIKED = BASE + "/dashboard/because-you-liked";
    private static final String DASHBOARD = BASE + "/dashboard";
    private static final String ENQUEUE = "/api/v1/admin/rec/enqueue";
    private static final String RPG = "Role-playing (RPG)";

    @BeforeEach
    void aliceOnPc() {
        ownsPlatform(ALICE, "PC");
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void freshPool(String userId, int fromId, int count, String genre) {
        for (int i = 0; i < count; i++) {
            poolRow(userId, fromId + i, genre + "-" + (fromId + i), 1, "0.8000", LocalDateTime.now(), genre);
        }
    }

    private ComputeQueueEntry queuedFor(String userId) {
        return queueRepository.findById(userId).orElseThrow();
    }

    // ── refusals without a token ──────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "POST " + PERSONALIZED, "POST " + GROUPED, "POST " + GENRE_ROW, "GET " + WILDCARD,
            "GET " + SIMILAR + "500", "GET " + BECAUSE_YOU_LIKED + "500", "GET " + ROLL_BECAUSE_YOU_LIKED,
            "POST " + DASHBOARD, "POST " + ENQUEUE + "?userId=" + BOB})
    void everyEndpointRefusesARequestWithoutATokenOrWithARejectedOne(String endpoint) throws Exception {
        String[] parts = endpoint.split(" ");
        MockHttpServletRequestBuilder request = parts[0].equals("GET") ? get(parts[1]) : json(post(parts[1]), "{\"genre\":\"RPG\"}");

        mvc.perform(request).andExpect(status().isUnauthorized());
        as("forged", parts[0].equals("GET") ? get(parts[1]) : json(post(parts[1]), "{\"genre\":\"RPG\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(queueRepository.count()).isZero();
        assertThat(LIBRARY_SERVICE.recorded()).isEmpty();
        assertThat(GAME_SERVICE.recorded()).isEmpty();
    }

    // ── /personalized ─────────────────────────────────────────────────────────

    @Test
    void personalizedServesTheCallersFreshPoolWithoutQueueingACompute() throws Exception {
        freshPool(ALICE, 1001, 3, RPG);

        as(ALICE_TOKEN, json(post(PERSONALIZED), "{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].igdbId", containsInAnyOrder(1001, 1002, 1003)))
                .andExpect(jsonPath("$[*].reason", everyItem(is("Based on your ratings"))))
                .andExpect(jsonPath("$[*].tier", everyItem(is(1))));

        assertThat(queueRepository.count()).isZero();
    }

    @Test
    void personalizedStillServesAStalePoolAndQueuesARecompute() throws Exception {
        poolRow(ALICE, 1001, "old-1", 1, "0.8000", LocalDateTime.now().minusHours(25), RPG);

        as(ALICE_TOKEN, json(post(PERSONALIZED), "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].igdbId", contains(1001)));

        assertThat(queuedFor(ALICE).getTargetGenre()).isNull();
    }

    // ── /personalized/grouped ─────────────────────────────────────────────────

    // Rows follow the library's genre counts, largest first; with fewer than five genres the popular row
    // fills in and the page is told why the shelves are few.
    @Test
    void groupedOrdersTheRowsByTheLibrarysGenresAndFillsWithPopularGames() throws Exception {
        freshPool(ALICE, 1001, 6, RPG);
        freshPool(ALICE, 1101, 4, "Shooter");
        profile(ALICE, Map.of(RPG, 5.0, "Shooter", 2.0));
        catalogGame(9001, "popular-1", "Puzzle", "PC");
        catalogGame(9002, "popular-2", "Puzzle", "PC");
        popular(9001, 9002);

        as(ALICE_TOKEN, post(GROUPED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value(1))
                .andExpect(jsonPath("$.rows[*].label", contains(RPG, "Shooter", "Popular on your platforms")))
                .andExpect(jsonPath("$.rows[0].games", hasSize(6)))
                .andExpect(jsonPath("$.rows[0].games[*].reason", everyItem(is("From your " + RPG + " ratings"))))
                .andExpect(jsonPath("$.rows[1].games", hasSize(4)))
                .andExpect(jsonPath("$.rows[2].fallback").value(true))
                .andExpect(jsonPath("$.rows[2].games[*].igdbId", contains(9001, 9002)))
                .andExpect(jsonPath("$.emptyMessage").isNotEmpty());
    }

    @Test
    void groupedOnAnEmptyPoolAnswersThePopularRowAndQueuesTheCompute() throws Exception {
        catalogGame(9001, "popular-1", "Puzzle", "PC");
        popular(9001);

        as(ALICE_TOKEN, json(post(GROUPED), "{\"recentlyShownIds\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value(3))
                .andExpect(jsonPath("$.rows[0].fallback").value(true))
                .andExpect(jsonPath("$.rows[0].games[*].igdbId", contains(9001)));

        assertThat(queuedFor(ALICE).getTargetGenre()).isNull();
    }

    // ── /personalized/grouped/genre ───────────────────────────────────────────

    @Test
    void aGenreRowServesThatGenreAndQueuesATopUpForIt() throws Exception {
        freshPool(ALICE, 1001, 3, RPG);
        freshPool(ALICE, 1101, 2, "Shooter");

        as(ALICE_TOKEN, json(post(GENRE_ROW), "{\"genre\":\"" + RPG + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genre").value(RPG))
                .andExpect(jsonPath("$.games[*].igdbId", containsInAnyOrder(1001, 1002, 1003)));

        assertThat(queuedFor(ALICE).getTargetGenre()).isEqualTo(RPG);
    }

    @Test
    void aGenreRowOnAnEmptyPoolAnswers204AndQueuesAFullCompute() throws Exception {
        as(ALICE_TOKEN, json(post(GENRE_ROW), "{\"genre\":\"" + RPG + "\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(queuedFor(ALICE).getTargetGenre()).isNull();
    }

    @Test
    void aGenreRowForAGenreThePoolLacksAnswers204() throws Exception {
        freshPool(ALICE, 1101, 2, "Shooter");

        as(ALICE_TOKEN, json(post(GENRE_ROW), "{\"genre\":\"" + RPG + "\"}"))
                .andExpect(status().isNoContent());
    }

    // ── /wildcard ─────────────────────────────────────────────────────────────

    // The catalog's random pick knows nothing of the user, so it is over-fetched and filtered here:
    // nothing the caller owns, nothing off the caller's platforms.
    @Test
    void wildcardLeavesOutWhatTheCallerOwnsAndWhatIsOffTheirPlatforms() throws Exception {
        owns(ALICE, 3003, "Hades", "PLAYING", "9", "Action", "PC");
        catalogGame(3001, "Celeste", "Platform", "PC");
        catalogGame(3002, "Splatoon", "Shooter", "Nintendo Switch");
        catalogGame(3003, "Hades", "Action", "PC");
        catalogGame(3004, "Tunic", "Adventure", "PC");
        catalogGame(3005, "Outer Wilds", "Adventure", "PC");
        randomGames(3001, 3002, 3003, 3004, 3005);

        as(ALICE_TOKEN, get(WILDCARD).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].igdbId", contains(3001, 3004)))
                .andExpect(jsonPath("$[*].reason", everyItem(is("Wild Card - something different"))));

        assertThat(GAME_SERVICE.recorded("GET", "/api/v1/games/random")).singleElement()
                .satisfies(call -> {
                    assertThat(call.query()).isEqualTo("limit=50&platform=PC");
                    assertThat(call.header("Authorization")).isEqualTo(bearer(ALICE_TOKEN));
                });
    }

    private String randomQuery() {
        return GAME_SERVICE.recorded("GET", "/api/v1/games/random").get(0).query();
    }

    // Fifty per platform is asked for, so every platform on the profile weighs the same in the hand.
    @Test
    void wildcardOnMineAsksForFiftyOnEachProfilePlatform() throws Exception {
        ownsPlatform(ALICE, "PlayStation 5");
        randomGames();

        as(ALICE_TOKEN, get(WILDCARD).param("scope", "mine")).andExpect(status().isOk());

        assertThat(randomQuery()).startsWith("limit=100&").contains("platform=PC", "platform=PlayStation%205");
    }

    @Test
    void wildcardOnMineNarrowsToAPickOnTheProfileAndIgnoresOneOffIt() throws Exception {
        ownsPlatform(ALICE, "PlayStation 5");
        randomGames();

        as(ALICE_TOKEN, get(WILDCARD).param("platform", "PlayStation 5", "Nintendo Switch")).andExpect(status().isOk());
        assertThat(randomQuery()).isEqualTo("limit=50&platform=PlayStation%205");

        GAME_SERVICE.reset();
        randomGames();
        as(ALICE_TOKEN, get(WILDCARD).param("platform", "Nintendo Switch")).andExpect(status().isOk());
        assertThat(randomQuery()).startsWith("limit=100&").doesNotContain("Switch");
    }

    // Splatoon is off Alice's PC profile, so only ALL deals it.
    @Test
    void wildcardOnAllWithNothingPickedDealsFromTheWholeCatalog() throws Exception {
        catalogGame(3001, "Celeste", "Platform", "PC");
        catalogGame(3002, "Splatoon", "Shooter", "Nintendo Switch");
        randomGames(3001, 3002);

        as(ALICE_TOKEN, get(WILDCARD).param("scope", "all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].igdbId", contains(3001, 3002)));

        assertThat(randomQuery()).isEqualTo("limit=50");
        assertThat(LIBRARY_SERVICE.recorded("GET", "/api/v1/library/platforms")).isEmpty();
    }

    @Test
    void wildcardOnAllDealsOnlyThePickedPlatformsEvenOffTheProfile() throws Exception {
        catalogGame(3001, "Celeste", "Platform", "PC");
        catalogGame(3002, "Splatoon", "Shooter", "Nintendo Switch");
        randomGames(3001, 3002);

        as(ALICE_TOKEN, get(WILDCARD).param("scope", "all").param("platform", "Nintendo Switch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].igdbId", contains(3002)));

        assertThat(randomQuery()).isEqualTo("limit=50&platform=Nintendo%20Switch");
    }

    // A profile without platforms has nothing to keep the hand on, so MINE deals from everything.
    @Test
    void wildcardOnMineForAProfileWithoutPlatformsDealsFromTheWholeCatalog() throws Exception {
        catalogGame(3002, "Splatoon", "Shooter", "Nintendo Switch");
        randomGames(3002);

        as(BOB_TOKEN, get(WILDCARD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].igdbId", contains(3002)));

        assertThat(randomQuery()).isEqualTo("limit=50");
    }

    @Test
    void wildcardSendsAPlatformNameWithAnAmpersandAsOneValue() throws Exception {
        randomGames();

        as(ALICE_TOKEN, get(WILDCARD).param("scope", "all").param("platform", "Game & Watch")).andExpect(status().isOk());

        assertThat(randomQuery()).isEqualTo("limit=50&platform=Game%20%26%20Watch");
    }

    // game-service answers at most 500, so eleven platforms share 500 rather than asking for 550.
    @Test
    void wildcardCapsTheDrawAtGameServicesCeiling() throws Exception {
        randomGames();
        String[] eleven = java.util.stream.IntStream.range(0, 11).mapToObj(i -> "Platform " + i).toArray(String[]::new);

        as(ALICE_TOKEN, get(WILDCARD).param("scope", "all").param("platform", eleven)).andExpect(status().isOk());

        assertThat(randomQuery()).startsWith("limit=500&");
    }

    @Test
    void wildcardAnswersEmptyWhenGameServiceFails() throws Exception {
        GAME_SERVICE.on("GET", "/api/v1/games/random", StubResponse.json(500, "{}"));

        as(ALICE_TOKEN, get(WILDCARD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    // ── /similar/{gameId} ─────────────────────────────────────────────────────

    @Test
    void similarAnswersTheCatalogsNeighboursWithoutTheGameItself() throws Exception {
        catalogGame(500, "Hades", "Action", "PC");
        catalogGame(501, "Bastion", "Action", "PC");
        catalogGame(502, "Transistor", "Action", "PC");
        similarTo(500, 501, 500, 502);

        as(ALICE_TOKEN, get(SIMILAR + "500").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].igdbId", contains(501, 502)))
                .andExpect(jsonPath("$[*].reason", everyItem(is("Similar to Hades"))))
                .andExpect(jsonPath("$[*].seedIgdbId", everyItem(is(500))));

        assertThat(GAME_SERVICE.recorded("GET", "/api/v1/games/500/similar")).singleElement()
                .satisfies(call -> assertThat(call.query()).isEqualTo("limit=5"));
    }

    @Test
    void similarForAGameTheCatalogDoesNotKnowAnswersEmpty() throws Exception {
        as(ALICE_TOKEN, get(SIMILAR + "404404"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    @Test
    void similarAnswers503WhenGameServiceFails() throws Exception {
        GAME_SERVICE.on("GET", "/api/v1/games/", StubResponse.json(500, "{}"));

        as(ALICE_TOKEN, get(SIMILAR + "500"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    // ── /because-you-liked/{gameId} ───────────────────────────────────────────

    @Test
    void becauseYouLikedFiltersTheNeighboursToWhatTheCallerDoesNotOwnOnTheirPlatforms() throws Exception {
        owns(ALICE, 501, "Bastion", "COMPLETED", "8", "Action", "PC");
        catalogGame(500, "Hades", "Action", "PC");
        catalogGame(501, "Bastion", "Action", "PC");
        catalogGame(502, "Transistor", "Action", "PC");
        catalogGame(503, "Pyre", "Action", "Nintendo Switch");
        similarTo(500, 501, 502, 503);

        as(ALICE_TOKEN, get(BECAUSE_YOU_LIKED + "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].igdbId", contains(502)))
                .andExpect(jsonPath("$[0].reason").value("Because you liked Hades"));
    }

    // ── /dashboard/because-you-liked ──────────────────────────────────────────

    @Test
    void rollingBecauseYouLikedPicksARatedSeedAndCarriesTheCallersRating() throws Exception {
        owns(ALICE, 600, "Disco Elysium", "COMPLETED", "9.5", RPG, "PC");
        catalogGame(600, "Disco Elysium", RPG, "PC");
        catalogGame(601, "Planescape: Torment", RPG, "PC");
        catalogGame(602, "Pentiment", RPG, "PC");
        similarTo(600, 601, 602);

        // The only eligible seed is excluded, so the roll falls back to it rather than answer nothing.
        as(ALICE_TOKEN, get(ROLL_BECAUSE_YOU_LIKED).param("excludeSeedIgdbIds", "600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basedOnIgdbId").value(600))
                .andExpect(jsonPath("$.basedOnGame").value("Disco Elysium"))
                .andExpect(jsonPath("$.recommendations[*].igdbId", containsInAnyOrder(601, 602)))
                .andExpect(jsonPath("$.recommendations[*].seedRating", everyItem(is(9.5))));
    }

    @Test
    void rollingBecauseYouLikedWithNoSeedRatedHighEnoughAnswers204() throws Exception {
        owns(ALICE, 600, "Disco Elysium", "COMPLETED", "6", RPG, "PC");
        owns(ALICE, 601, "Planescape: Torment", "WISHLIST", "10", RPG, "PC");

        as(ALICE_TOKEN, get(ROLL_BECAUSE_YOU_LIKED))
                .andExpect(status().isNoContent());
    }

    // ── /dashboard ────────────────────────────────────────────────────────────

    @Test
    void theDashboardAnswersAllThreeSectionsInOneResponse() throws Exception {
        freshPool(ALICE, 1001, 3, RPG);
        owns(ALICE, 600, "Disco Elysium", "COMPLETED", "9", RPG, "PC");
        catalogGame(600, "Disco Elysium", RPG, "PC");
        catalogGame(601, "Pentiment", RPG, "PC");
        catalogGame(3001, "Celeste", "Platform", "PC");
        similarTo(600, 601);
        randomGames(3001);

        as(ALICE_TOKEN, post(DASHBOARD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations[*].igdbId", containsInAnyOrder(1001, 1002, 1003)))
                .andExpect(jsonPath("$.wildcard[*].igdbId", contains(3001)))
                .andExpect(jsonPath("$.becauseYouLiked[0].basedOnIgdbId").value(600))
                .andExpect(jsonPath("$.becauseYouLiked[0].recommendations[*].igdbId", contains(601)));
    }

    // One section failing leaves the others standing rather than failing the page.
    @Test
    void theDashboardAnswersTheSectionsThatWorkWhenGameServiceFails() throws Exception {
        freshPool(ALICE, 1001, 2, RPG);
        owns(ALICE, 600, "Disco Elysium", "COMPLETED", "9", RPG, "PC");
        GAME_SERVICE.on("GET", "/api/v1/games/", StubResponse.json(500, "{}"));

        as(ALICE_TOKEN, json(post(DASHBOARD), "{\"recentlyShownIds\":[1001]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations[*].igdbId", containsInAnyOrder(1001, 1002)))
                .andExpect(jsonPath("$.wildcard", empty()))
                .andExpect(jsonPath("$.becauseYouLiked", empty()));
    }

    // ── /admin/rec/enqueue ────────────────────────────────────────────────────

    @Test
    void anAdminQueuesAFullComputeForTheNamedUser() throws Exception {
        as(ADMIN_TOKEN, post(ENQUEUE).param("userId", BOB))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.userId").value(BOB))
                .andExpect(jsonPath("$.queuedAt").isNotEmpty());

        assertThat(queuedFor(BOB).getTargetGenre()).isNull();
        assertThat(queueRepository.count()).isEqualTo(1);
    }

    @Test
    void aUserWithoutTheAdminRoleCannotQueueACompute() throws Exception {
        as(ALICE_TOKEN, post(ENQUEUE).param("userId", BOB))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString(BOB))));

        assertThat(queueRepository.count()).isZero();
    }

    // ── the token is what reaches library-service ─────────────────────────────

    @Test
    void aLibraryServiceThatRefusesTheForwardedTokenAnswers503() throws Exception {
        LIBRARY_SERVICE.on("GET", "/api/v1/library/", StubResponse.json(401, "{}"));
        catalogGame(500, "Hades", "Action", "PC");
        similarTo(500);

        as(ALICE_TOKEN, get(BECAUSE_YOU_LIKED + "500"))
                .andExpect(status().isServiceUnavailable());
        assertThat(LIBRARY_SERVICE.recorded("GET", "/api/v1/library/")).isNotEmpty()
                .allSatisfy(call -> assertThat(call.header("Authorization")).isEqualTo(bearer(ALICE_TOKEN)));
    }
}
