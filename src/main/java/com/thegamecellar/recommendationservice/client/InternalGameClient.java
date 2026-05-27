package com.thegamecellar.recommendationservice.client;

import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.game.GameSearchDTO;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;

// Worker compute path uses this instead of GameServiceClient (which forwards a user JWT).
// Hits /internal/games/* with the X-Internal-Token shared secret. Subset of GameServiceClient
// covering only the worker-needed reads (getById, popular, randomQualityByGenre).
@Slf4j
@Component
public class InternalGameClient {

    private final RestTemplate restTemplate;
    private final String gameServiceUrl;
    private final String internalToken;

    public InternalGameClient(RestTemplate restTemplate,
                              @Value("${services.game-service.url}") String gameServiceUrl,
                              @Value("${security.internal.token:}") String internalToken) {
        this.restTemplate = restTemplate;
        this.gameServiceUrl = gameServiceUrl;
        this.internalToken = internalToken;
    }

    public GameDTO getGameById(Integer igdbId) {
        try {
            ResponseEntity<GameDTO> response = restTemplate.exchange(
                    gameServiceUrl + "/internal/games/{igdbId}",
                    HttpMethod.GET, buildRequest(), GameDTO.class, igdbId);
            return response.getBody();
        } catch (HttpClientErrorException.NotFound ex) {
            return null;
        } catch (RestClientException ex) {
            log.warn("Internal game-service getGameById({}) failed: {}", igdbId, ex.getClass().getSimpleName());
            return null;
        }
    }

    public List<GameDTO> getPopularGames(String platform) {
        try {
            UriComponentsBuilder b = UriComponentsBuilder
                    .fromUriString(gameServiceUrl + "/internal/games/popular");
            if (platform != null && !platform.isBlank()) {
                b.queryParam("platform", platform);
            }
            ResponseEntity<GameSearchDTO> response = restTemplate.exchange(
                    b.build().encode().toUri(),
                    HttpMethod.GET, buildRequest(), GameSearchDTO.class);
            GameSearchDTO body = response.getBody();
            if (body == null || body.getGames() == null) return Collections.emptyList();
            return body.getGames();
        } catch (RestClientException ex) {
            log.warn("Internal game-service getPopularGames failed: {}", ex.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    public List<GameDTO> randomQualityByGenre(String genre, java.math.BigDecimal minRating, int minVotes, int limit) {
        try {
            java.net.URI uri = UriComponentsBuilder
                    .fromUriString(gameServiceUrl + "/internal/games/random-quality")
                    .queryParam("genre", genre)
                    .queryParam("minRating", minRating.toPlainString())
                    .queryParam("minVotes", minVotes)
                    .queryParam("limit", limit)
                    .build().encode().toUri();
            ResponseEntity<GameSearchDTO> response = restTemplate.exchange(
                    uri, HttpMethod.GET, buildRequest(), GameSearchDTO.class);
            GameSearchDTO body = response.getBody();
            if (body == null || body.getGames() == null) return Collections.emptyList();
            return body.getGames();
        } catch (RestClientException ex) {
            log.warn("Internal game-service randomQualityByGenre('{}') failed: {}", genre, ex.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private HttpEntity<Void> buildRequest() {
        HttpHeaders headers = new HttpHeaders();
        if (internalToken != null && !internalToken.isBlank()) {
            headers.set("X-Internal-Token", internalToken);
        }
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            headers.set("X-Request-ID", requestId);
        }
        return new HttpEntity<>(headers);
    }
}
