package com.thegamecellar.recommendationservice.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thegamecellar.recommendationservice.exception.GlobalExceptionHandler;
import com.thegamecellar.recommendationservice.service.WildCardService;
import com.thegamecellar.recommendationservice.support.RecommendationServiceTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Malformed requests sent the way a client sends them: over HTTP with a bearer token through the
// real security chain, against Postgres running the real migrations. A client mistake answers 400
// and never logs at ERROR, because sentry-logback turns every ERROR line into a Sentry event.
class RequestValidationTest extends RecommendationServiceTestBase {

    private static final String PERSONALIZED = "/api/v1/recommendations/personalized";
    private static final String GROUPED = "/api/v1/recommendations/personalized/grouped";
    private static final String GENRE_ROW = "/api/v1/recommendations/personalized/grouped/genre";
    private static final String DASHBOARD = "/api/v1/recommendations/dashboard";
    private static final String WILDCARD = "/api/v1/recommendations/wildcard";
    private static final String SIMILAR = "/api/v1/recommendations/similar/";
    private static final String BECAUSE_YOU_LIKED = "/api/v1/recommendations/because-you-liked/";
    private static final String ROLL_BECAUSE_YOU_LIKED = "/api/v1/recommendations/dashboard/because-you-liked";
    private static final String ENQUEUE = "/api/v1/admin/rec/enqueue";

    // Mocked only so the last test can make a real fault happen; validation runs before the service is reached.
    @MockitoBean private WildCardService wildCardService;

    private final Logger handlerLog = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void logCapture() {
        logged.start();
        handlerLog.addAppender(logged);
    }

    @AfterEach
    void stopLogCapture() {
        handlerLog.detachAppender(logged);
    }

    private static String tooManyShownIds() {
        return IntStream.rangeClosed(1, 5001).mapToObj(Integer::toString)
                .collect(Collectors.joining(",", "{\"recentlyShownIds\":[", "]}"));
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
        expectBadRequest(post(PERSONALIZED).contentType(MediaType.APPLICATION_JSON).content("{\"limit\":0}"), ALICE_TOKEN);
        expectBadRequest(post(PERSONALIZED).contentType(MediaType.APPLICATION_JSON).content("{\"limit\":101}"), ALICE_TOKEN);
        expectBadRequest(post(GENRE_ROW).contentType(MediaType.APPLICATION_JSON).content("{\"genre\":\"\"}"), ALICE_TOKEN);

        assertThat(errorLines()).isEmpty();
    }

    @Test
    void aMalformedOrMissingBodyAnswers400WithoutAnErrorLog() throws Exception {
        expectBadRequest(post(PERSONALIZED).contentType(MediaType.APPLICATION_JSON).content("{not json"), ALICE_TOKEN);
        expectBadRequest(post(PERSONALIZED).contentType(MediaType.APPLICATION_JSON).content("{\"limit\":\"ten\"}"), ALICE_TOKEN);
        expectBadRequest(post(PERSONALIZED).contentType(MediaType.APPLICATION_JSON), ALICE_TOKEN);
        expectBadRequest(post(GENRE_ROW).contentType(MediaType.APPLICATION_JSON), ALICE_TOKEN);

        assertThat(errorLines()).isEmpty();
    }

    // Both bodies are optional, but one that is sent is still validated, and a refused one queues no compute.
    @Test
    void anInvalidOptionalBodyOnTheGroupedOrDashboardReadAnswers400WithoutAnErrorLog() throws Exception {
        for (String path : List.of(GROUPED, DASHBOARD)) {
            expectBadRequest(post(path).contentType(MediaType.APPLICATION_JSON).content(tooManyShownIds()), ALICE_TOKEN);
            expectBadRequest(post(path).contentType(MediaType.APPLICATION_JSON).content("{not json"), ALICE_TOKEN);
            expectBadRequest(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"recentlyShownIds\":\"abc\"}"), ALICE_TOKEN);
        }

        assertThat(errorLines()).isEmpty();
        assertThat(queueRepository.count()).isZero();
    }

    @Test
    void aPathOrQueryValueOfTheWrongTypeAnswers400WithoutAnErrorLog() throws Exception {
        expectBadRequest(get(SIMILAR + "abc"), ALICE_TOKEN);
        expectBadRequest(get(BECAUSE_YOU_LIKED + "abc"), ALICE_TOKEN);
        expectBadRequest(get(WILDCARD).param("limit", "abc"), ALICE_TOKEN);
        expectBadRequest(get(ROLL_BECAUSE_YOU_LIKED).param("excludeSeedIgdbIds", "abc"), ALICE_TOKEN);

        assertThat(errorLines()).isEmpty();
    }

    @Test
    void aValueOutOfRangeAnswers400WithoutAnErrorLog() throws Exception {
        expectBadRequest(get(WILDCARD).param("limit", "0"), ALICE_TOKEN);
        expectBadRequest(get(WILDCARD).param("limit", "51"), ALICE_TOKEN);
        expectBadRequest(get(SIMILAR + "0"), ALICE_TOKEN);

        assertThat(errorLines()).isEmpty();
    }

    @Test
    void theAdminEnqueueWithoutAUserIdAnswers400WithoutAnErrorLog() throws Exception {
        expectBadRequest(post(ENQUEUE), ADMIN_TOKEN);
        expectBadRequest(post(ENQUEUE).param("userId", " "), ADMIN_TOKEN);

        assertThat(errorLines()).isEmpty();
    }

    @Test
    void aWrongMethodOrMediaTypeAnswersItsOwnStatusWithoutAnErrorLog() throws Exception {
        send(get(PERSONALIZED), ALICE_TOKEN)
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(jsonPath("$.status").value(405));
        send(post(PERSONALIZED).contentType(MediaType.TEXT_PLAIN).content("10"), ALICE_TOKEN)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
        send(get(WILDCARD).accept(MediaType.APPLICATION_XML), ALICE_TOKEN)
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

        send(get(WILDCARD), ALICE_TOKEN)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));

        assertThat(errorLines()).hasSize(1);
    }
}
