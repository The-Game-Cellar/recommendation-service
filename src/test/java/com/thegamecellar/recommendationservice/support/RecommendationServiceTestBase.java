package com.thegamecellar.recommendationservice.support;

import com.thegamecellar.recommendationservice.model.entity.ComputeQueueEntry;
import com.thegamecellar.recommendationservice.model.entity.PoolHolding;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import com.thegamecellar.recommendationservice.repository.ComputeQueueRepository;
import com.thegamecellar.recommendationservice.repository.PoolHoldingRepository;
import com.thegamecellar.recommendationservice.repository.UserCandidatePoolRepository;
import com.thegamecellar.recommendationservice.repository.UserProfileSnapshotRepository;
import com.thegamecellar.recommendationservice.support.StubHttpServer.RecordedRequest;
import com.thegamecellar.recommendationservice.support.StubHttpServer.StubResponse;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;

// One Spring context, one Postgres and one Redis for the whole suite: the real migrations build the
// schema, Hibernate validates against it, and the caches and the library-write channel run on a real
// Redis. Only what lies outside the service is replaced: the JwtDecoder (a token is whatever a test
// issues), and game-service and library-service, stubs at their URLs so rec-service's own HTTP clients
// do the talking.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class RecommendationServiceTestBase {

    // Started once and never stopped: every test class shares them, and Testcontainers removes them at JVM exit.
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rec_test_db")
            .withUsername("test")
            .withPassword("test");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    protected static final StubHttpServer GAME_SERVICE = StubHttpServer.start();
    protected static final StubHttpServer LIBRARY_SERVICE = StubHttpServer.start();

    protected static final RedisCommands<String, String> REDIS_COMMANDS;

    static {
        POSTGRES.start();
        REDIS.start();
        REDIS_COMMANDS = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379))
                .connect().sync();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("services.game-service.url", GAME_SERVICE::baseUrl);
        registry.add("services.library-service.url", LIBRARY_SERVICE::baseUrl);
    }

    protected static final String ALICE = "11111111-0000-4000-8000-000000000001";
    protected static final String BOB = "22222222-0000-4000-8000-000000000002";
    protected static final String ADMIN = "aaaaaaaa-0000-4000-8000-00000000000a";
    protected static final String ALICE_TOKEN = "alice-token";
    protected static final String BOB_TOKEN = "bob-token";
    protected static final String ADMIN_TOKEN = "admin-token";
    protected static final String INTERNAL_HEADER = "X-Internal-Token";
    protected static final String INTERNAL_TOKEN = "test-internal-token";
    protected static final List<String> TABLES = List.of("compute_queue", "pool_holding", "user_candidate_pool", "user_profiles");

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserCandidatePoolRepository poolRepository;
    @Autowired protected UserProfileSnapshotRepository profileRepository;
    @Autowired protected ComputeQueueRepository queueRepository;
    @Autowired protected PoolHoldingRepository holdingRepository;

    @MockitoBean protected JwtDecoder jwtDecoder;

    private final Map<String, String> userByToken = new ConcurrentHashMap<>();
    private final Map<String, Library> libraries = new ConcurrentHashMap<>();
    private final Map<Integer, String> catalog = new ConcurrentHashMap<>();

    @BeforeEach
    void tokensAndStubs() {
        lenient().when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("unknown token"));
        issue(ALICE_TOKEN, ALICE, "USER");
        issue(BOB_TOKEN, BOB, "USER");
        issue(ADMIN_TOKEN, ADMIN, "USER", "ADMIN");
        GAME_SERVICE.reset();
        LIBRARY_SERVICE.reset();
        libraryRoutes();
    }

    @AfterEach
    void emptyEveryTableAndCache() {
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class);
        jdbc.execute("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        REDIS_COMMANDS.flushall();
    }

    // Keycloak-shaped, realm roles included, so the app's own role converter runs as in production.
    protected void issue(String token, String subject, String... realmRoles) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .build();
        doReturn(jwt).when(jwtDecoder).decode(token);
        userByToken.put(token, subject);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected int rows(String table, String userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
    }

    // ── library-service stub ──────────────────────────────────────────────────

    private static final class Library {
        final List<String> games = new ArrayList<>();
        final List<String> platforms = new ArrayList<>();
        final List<String> genrePreferences = new ArrayList<>();
    }

    private Library library(String userId) {
        return libraries.computeIfAbsent(userId, id -> new Library());
    }

    protected void owns(String userId, int igdbId, String name, String status, String rating, String genre, String platform) {
        library(userId).games.add("{\"id\":%d,\"igdbGameId\":%d,\"gameName\":\"%s\",\"status\":\"%s\",\"rating\":%s,\"platform\":\"%s\",\"platforms\":[\"%s\"],\"genres\":[\"%s\"],\"themes\":[],\"tags\":[]}"
                .formatted(igdbId, igdbId, name, status, rating == null ? "null" : rating, platform, platform, genre));
    }

    protected void ownsPlatform(String userId, String platformName) {
        Library library = library(userId);
        library.platforms.add("{\"id\":%d,\"platformName\":\"%s\",\"isPrimary\":%s}"
                .formatted(library.platforms.size() + 1, platformName, library.platforms.isEmpty()));
    }

    protected void prefersGenre(String userId, String genre) {
        library(userId).genrePreferences.add("{\"genreName\":\"%s\"}".formatted(genre));
    }

    // The user-facing endpoints answer for the user behind the forwarded bearer token, the internal ones
    // for the user in the path once the shared token checks out; anything else is refused as library-service would.
    private void libraryRoutes() {
        userFacing("/api/v1/library/games", library -> library.games);
        userFacing("/api/v1/library/platforms", library -> library.platforms);
        userFacing("/api/v1/library/genre-preferences", library -> library.genrePreferences);
        userFacing("/api/v1/library/tag-preferences", library -> List.of());
        userFacing("/api/v1/library/release-year-preferences", library -> List.of());
        LIBRARY_SERVICE.on("GET", "/internal/library/users/", request -> {
            if (!INTERNAL_TOKEN.equals(request.header(INTERNAL_HEADER))) return StubResponse.json(401, "{}");
            String[] parts = request.path().substring("/internal/library/users/".length()).split("/", 2);
            Library library = library(parts[0]);
            return switch (parts.length < 2 ? "" : parts[1]) {
                case "games" -> array(library.games);
                case "platforms" -> array(library.platforms);
                case "preferences/genres" -> array(library.genrePreferences);
                case "preferences/tags", "preferences/release-years" -> array(List.of());
                default -> StubResponse.json(404, "{}");
            };
        });
    }

    private void userFacing(String path, Function<Library, List<String>> body) {
        LIBRARY_SERVICE.onExact("GET", path, request -> {
            String userId = userBehind(request);
            if (userId == null) return StubResponse.json(401, "{}");
            return array(body.apply(library(userId)));
        });
    }

    private String userBehind(RecordedRequest request) {
        String authorization = request.header("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        return userByToken.get(authorization.substring("Bearer ".length()));
    }

    // ── game-service stub ─────────────────────────────────────────────────────

    // Above the quality bar on every axis, so the compute never drops a fixture game for being obscure.
    protected void catalogGame(int igdbId, String name, String genre, String platform) {
        String json = ("{\"igdbId\":%d,\"name\":\"%s\",\"rating\":8.50,\"totalRating\":8.50,\"totalRatingCount\":500,"
                + "\"backgroundImage\":\"%s.jpg\",\"released\":\"2021-05-05\",\"genres\":[\"%s\"],\"platforms\":[\"%s\"],"
                + "\"themes\":[],\"tags\":[],\"similarGameIds\":[]}")
                .formatted(igdbId, name, igdbId, genre, platform);
        catalog.put(igdbId, json);
        GAME_SERVICE.onExact("GET", "/api/v1/games/" + igdbId, StubResponse.json(200, json));
        GAME_SERVICE.onExact("GET", "/internal/games/" + igdbId, request ->
                INTERNAL_TOKEN.equals(request.header(INTERNAL_HEADER)) ? StubResponse.json(200, json) : StubResponse.json(401, "{}"));
    }

    protected void similarTo(int igdbId, int... similarIds) {
        GAME_SERVICE.onExact("GET", "/api/v1/games/" + igdbId + "/similar", StubResponse.json(200, gamesArray(similarIds)));
    }

    protected void popular(int... igdbIds) {
        GAME_SERVICE.onExact("GET", "/api/v1/games/popular", StubResponse.json(200, gamesPage(igdbIds)));
        internal("/internal/games/popular", igdbIds);
    }

    protected void randomGames(int... igdbIds) {
        GAME_SERVICE.onExact("GET", "/api/v1/games/random", StubResponse.json(200, gamesPage(igdbIds)));
    }

    // Whatever genre is asked for, the same bag answers, so the profile is all that separates the candidates.
    protected void randomQuality(int... igdbIds) {
        internal("/internal/games/random-quality", igdbIds);
    }

    private void internal(String path, int... igdbIds) {
        GAME_SERVICE.onExact("GET", path, request ->
                INTERNAL_TOKEN.equals(request.header(INTERNAL_HEADER)) ? StubResponse.json(200, gamesPage(igdbIds)) : StubResponse.json(401, "{}"));
    }

    private String gamesArray(int... igdbIds) {
        return Arrays.stream(igdbIds).mapToObj(catalog::get).collect(Collectors.joining(",", "[", "]"));
    }

    private String gamesPage(int... igdbIds) {
        return "{\"games\":%s,\"totalCount\":%d,\"page\":0,\"pageSize\":%d}".formatted(gamesArray(igdbIds), igdbIds.length, igdbIds.length);
    }

    private static StubResponse array(List<String> items) {
        return StubResponse.json(200, String.join(",", items).transform(s -> "[" + s + "]"));
    }

    // ── rec_db seeding ────────────────────────────────────────────────────────

    protected UserCandidatePool poolRow(String userId, int igdbId, String name, int tier, String baseScore,
                                        LocalDateTime computedAt, String... genres) {
        return poolRepository.save(UserCandidatePool.builder().userId(userId).igdbId(igdbId).name(name)
                .baseScore(new BigDecimal(baseScore)).tier((short) tier).rating(new BigDecimal("8.50"))
                .backgroundImage(name + ".jpg").genres(List.of(genres)).platforms(List.of("PC"))
                .sharedTags(List.of()).computedAt(computedAt).build());
    }

    protected UserProfileSnapshot profile(String userId, Map<String, Double> libraryGenreCounts) {
        return profileRepository.save(UserProfileSnapshot.builder().userId(userId)
                .genreWeights(libraryGenreCounts).tagWeights(Map.of()).platformWeights(Map.of("PC", 1.0))
                .decadeWeights(Map.of()).libraryGenreCounts(libraryGenreCounts).ratedCount(5)
                .updatedAt(LocalDateTime.now()).build());
    }

    protected PoolHolding holding(String userId, String genre, int igdbId, LocalDateTime releasedAt) {
        return holdingRepository.save(PoolHolding.builder().userId(userId).genre(genre).igdbId(igdbId).releasedAt(releasedAt).build());
    }

    protected ComputeQueueEntry queued(String userId, LocalDateTime queuedAt, int attempts, String targetGenre) {
        return queueRepository.save(ComputeQueueEntry.builder().userId(userId).queuedAt(queuedAt)
                .attempts((short) attempts).targetGenre(targetGenre).build());
    }
}
