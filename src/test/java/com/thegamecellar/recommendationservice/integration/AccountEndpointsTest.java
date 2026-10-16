package com.thegamecellar.recommendationservice.integration;

import com.thegamecellar.recommendationservice.model.entity.ComputeQueueEntry;
import com.thegamecellar.recommendationservice.model.entity.PoolHolding;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import com.thegamecellar.recommendationservice.repository.ComputeQueueRepository;
import com.thegamecellar.recommendationservice.repository.PoolHoldingRepository;
import com.thegamecellar.recommendationservice.repository.UserCandidatePoolRepository;
import com.thegamecellar.recommendationservice.repository.UserProfileSnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The two GDPR endpoints driven the way the gateway calls them: an HTTP request with a bearer
// token through the real security chain, against Postgres running the real migrations. Only
// the decoder is mocked, so a token is whatever a test issues and anything else is rejected.
// The workers' first run is pushed past the test, or they would dequeue the seeded queue rows.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.data.redis.host=localhost",
        "recommendation.ratelimit.distributed=false",
        "recommendation.library-write-subscriber.enabled=false",
        "recommendation.worker.initial-delay-ms=3600000",
        "recommendation.stale-scan.initial-delay-ms=3600000"
})
class AccountEndpointsTest {

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

    private static final String ALICE = "11111111-0000-4000-8000-000000000001";
    private static final String BOB = "22222222-0000-4000-8000-000000000002";
    private static final String EXPORT = "/api/v1/recommendations/account/export";
    private static final String ACCOUNT = "/api/v1/recommendations/account";

    @MockitoBean private JwtDecoder jwtDecoder;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserProfileSnapshotRepository profiles;
    @Autowired private UserCandidatePoolRepository pool;
    @Autowired private PoolHoldingRepository holdings;
    @Autowired private ComputeQueueRepository queue;

    @BeforeEach
    void tokensAndRowsForBothUsers() {
        lenient().when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("unknown token"));
        issue("alice-token", ALICE);
        issue("bob-token", BOB);
        seed(ALICE, 1001, "Hollow Knight", "Action");
        seed(BOB, 1002, "Stardew Valley", "Simulator");
    }

    @AfterEach
    void emptyEveryTable() {
        for (String table : List.of("compute_queue", "pool_holding", "user_candidate_pool", "user_profiles")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    // Keycloak-shaped, realm roles included, so the app's own role converter runs as in production.
    private void issue(String token, String subject) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("realm_access", Map.of("roles", List.of("USER")))
                .build();
        doReturn(jwt).when(jwtDecoder).decode(token);
    }

    private void seed(String userId, int igdbId, String gameName, String genre) {
        LocalDateTime now = LocalDateTime.now();
        profiles.save(UserProfileSnapshot.builder().userId(userId)
                .genreWeights(Map.of(genre, 0.9)).tagWeights(Map.of(genre + "-tag", 0.4))
                .platformWeights(Map.of("PC", 1.0)).decadeWeights(Map.of("2010s", 0.7))
                .libraryGenreCounts(Map.of(genre, 3.0)).ratedCount(5).updatedAt(now).build());
        pool.save(UserCandidatePool.builder().userId(userId).igdbId(igdbId).name(gameName)
                .baseScore(new BigDecimal("0.8500")).tier((short) 1).rating(new BigDecimal("8.50"))
                .genres(List.of(genre)).platforms(List.of("PC")).seedIgdbId(7).seedName("Seed")
                .seedRating(new BigDecimal("9.0")).sharedTags(List.of(genre)).computedAt(now).build());
        holdings.save(PoolHolding.builder().userId(userId).genre(genre).igdbId(igdbId + 1).releasedAt(now).build());
        queue.save(ComputeQueueEntry.builder().userId(userId).queuedAt(now).attempts((short) 2).targetGenre(genre).build());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private int rows(String table, String userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    void exportWithoutATokenOrWithARejectedOneIsRefused() throws Exception {
        mvc.perform(get(EXPORT)).andExpect(status().isUnauthorized());
        mvc.perform(get(EXPORT).header(HttpHeaders.AUTHORIZATION, bearer("forged"))).andExpect(status().isUnauthorized());
    }

    // Every row of the four tables for the caller, each column included, and nothing of Bob's.
    @Test
    void exportHoldsEveryRowOfTheCallerAndNoneOfAnotherUsers() throws Exception {
        mvc.perform(get(EXPORT).header(HttpHeaders.AUTHORIZATION, bearer("alice-token")))
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

        for (String table : List.of("compute_queue", "pool_holding", "user_candidate_pool", "user_profiles")) {
            assertThat(rows(table, ALICE)).as(table).isEqualTo(1);
        }
    }

    // The caller is taken from the token, so the purge empties all four tables for Alice and
    // leaves every one of Bob's rows where it was.
    @Test
    void deletePurgesEveryTableForTheCallerAndLeavesAnotherUserAlone() throws Exception {
        mvc.perform(delete(ACCOUNT).header(HttpHeaders.AUTHORIZATION, bearer("alice-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueRemoved").value(1))
                .andExpect(jsonPath("$.holdingRemoved").value(1))
                .andExpect(jsonPath("$.poolRemoved").value(1))
                .andExpect(jsonPath("$.profileRemoved").value(1));

        for (String table : List.of("compute_queue", "pool_holding", "user_candidate_pool", "user_profiles")) {
            assertThat(rows(table, ALICE)).as(table + " for the caller").isZero();
            assertThat(rows(table, BOB)).as(table + " for another user").isEqualTo(1);
        }
    }
}
