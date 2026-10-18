package com.thegamecellar.recommendationservice.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thegamecellar.recommendationservice.exception.GlobalExceptionHandler;
import com.thegamecellar.recommendationservice.service.WildCardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Malformed requests sent the way a client sends them: over HTTP with a bearer token through the
// real security chain, against Postgres running the real migrations. A client mistake answers 400
// and never logs at ERROR, because sentry-logback turns every ERROR line into a Sentry event.
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
class RequestValidationTest {

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

    private static final String PERSONALIZED = "/api/v1/recommendations/personalized";
    private static final String GENRE_ROW = "/api/v1/recommendations/personalized/grouped/genre";
    private static final String WILDCARD = "/api/v1/recommendations/wildcard";
    private static final String SIMILAR = "/api/v1/recommendations/similar/";
    private static final String BECAUSE_YOU_LIKED = "/api/v1/recommendations/because-you-liked/";
    private static final String ROLL_BECAUSE_YOU_LIKED = "/api/v1/recommendations/dashboard/because-you-liked";
    private static final String ENQUEUE = "/api/v1/admin/rec/enqueue";

    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private WildCardService wildCardService;

    @Autowired private MockMvc mvc;

    private final Logger handlerLog = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void tokensAndLogCapture() {
        lenient().when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("unknown token"));
        issue("user-token", "11111111-0000-4000-8000-000000000001", "USER");
        issue("admin-token", "99999999-0000-4000-8000-000000000009", "ADMIN");
        logged.start();
        handlerLog.addAppender(logged);
    }

    @AfterEach
    void stopLogCapture() {
        handlerLog.detachAppender(logged);
    }

    // Keycloak-shaped, realm roles included, so the app's own role converter runs as in production.
    private void issue(String token, String subject, String role) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
        doReturn(jwt).when(jwtDecoder).decode(token);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void expectBadRequest(MockHttpServletRequestBuilder request, String token) throws Exception {
        send(request, token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    private List<ILoggingEvent> errorLines() {
        return logged.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    @Test
    void aBodyThatFailsValidationAnswers400WithoutAnErrorLog() throws Exception {
        expectBadRequest(post(PERSONALIZED).contentType(MediaType.APPLICATION_JSON).content("{\"limit\":0}"), "user-token");
        expectBadRequest(post(PERSONALIZED).contentType(MediaType.APPLICATION_JSON).content("{\"limit\":101}"), "user-token");
        expectBadRequest(post(GENRE_ROW).contentType(MediaType.APPLICATION_JSON).content("{\"genre\":\"\"}"), "user-token");

        assertThat(errorLines()).isEmpty();
    }

    @Test
    void aMalformedOrMissingBodyAnswers400WithoutAnErrorLog() throws Exception {
        expectBadRequest(post(PERSONALIZED).contentType(MediaType.APPLICATION_JSON).content("{not json"), "user-token");
        expectBadRequest(post(PERSONALIZED).contentType(MediaType.APPLICATION_JSON).content("{\"limit\":\"ten\"}"), "user-token");
        expectBadRequest(post(PERSONALIZED).contentType(MediaType.APPLICATION_JSON), "user-token");
        expectBadRequest(post(GENRE_ROW).contentType(MediaType.APPLICATION_JSON), "user-token");

        assertThat(errorLines()).isEmpty();
    }

    @Test
    void aPathOrQueryValueOfTheWrongTypeAnswers400WithoutAnErrorLog() throws Exception {
        expectBadRequest(get(SIMILAR + "abc"), "user-token");
        expectBadRequest(get(BECAUSE_YOU_LIKED + "abc"), "user-token");
        expectBadRequest(get(WILDCARD).param("limit", "abc"), "user-token");
        expectBadRequest(get(ROLL_BECAUSE_YOU_LIKED).param("excludeSeedIgdbIds", "abc"), "user-token");

        assertThat(errorLines()).isEmpty();
    }

    @Test
    void aValueOutOfRangeAnswers400WithoutAnErrorLog() throws Exception {
        expectBadRequest(get(WILDCARD).param("limit", "0"), "user-token");
        expectBadRequest(get(WILDCARD).param("limit", "51"), "user-token");
        expectBadRequest(get(SIMILAR + "0"), "user-token");

        assertThat(errorLines()).isEmpty();
    }

    @Test
    void theAdminEnqueueWithoutAUserIdAnswers400WithoutAnErrorLog() throws Exception {
        expectBadRequest(post(ENQUEUE), "admin-token");
        expectBadRequest(post(ENQUEUE).param("userId", " "), "admin-token");

        assertThat(errorLines()).isEmpty();
    }

    @Test
    void aWrongMethodOrMediaTypeAnswersItsOwnStatusWithoutAnErrorLog() throws Exception {
        send(get(PERSONALIZED), "user-token")
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(jsonPath("$.status").value(405));
        send(post(PERSONALIZED).contentType(MediaType.TEXT_PLAIN).content("10"), "user-token")
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
        send(get(WILDCARD).accept(MediaType.APPLICATION_XML), "user-token")
                .andExpect(status().isNotAcceptable());

        assertThat(errorLines()).isEmpty();
    }

    @Test
    void malformedInputWithoutATokenIsRefusedBeforeValidation() throws Exception {
        mvc.perform(post(PERSONALIZED).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(SIMILAR + "abc")).andExpect(status().isUnauthorized());
    }

    @Test
    void aRealFaultStillAnswers500AndLogsAtError() throws Exception {
        when(wildCardService.getWildCard(anyString(), anyInt())).thenThrow(new IllegalStateException("boom"));

        send(get(WILDCARD), "user-token")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));

        assertThat(errorLines()).hasSize(1);
    }
}
