package com.thegamecellar.recommendationservice.client;

import com.thegamecellar.recommendationservice.exception.ServiceCommunicationException;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.game.GameSearchDTO;
import lombok.RequiredArgsConstructor;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class GameServiceClient {

    private final RestTemplate restTemplate;

    @Value("${services.game-service.url}")
    private String gameServiceUrl;

    public GameDTO getGameById(Integer igdbId, String bearerToken) {
        try {
            ResponseEntity<GameDTO> response = restTemplate.exchange(
                    gameServiceUrl + "/api/v1/games/{igdbId}",
                    HttpMethod.GET,
                    buildRequest(bearerToken),
                    GameDTO.class,
                    igdbId
            );
            return response.getBody();
        } catch (HttpClientErrorException.NotFound ex) {
            log.info("Game {} not found in Game Service (404), returning null for graceful skip", igdbId);
            return null;
        } catch (RestClientException ex) {
            String statusHint = (ex instanceof HttpClientErrorException hce) ? " " + hce.getStatusCode() : "";
            log.warn("Failed to fetch game {} from Game Service: {}{}", igdbId, ex.getClass().getSimpleName(), statusHint);
            throw new ServiceCommunicationException("Game Service unavailable", ex);
        }
    }

    public List<GameDTO> getPopularGames(String platform, String bearerToken) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromUriString(gameServiceUrl + "/api/v1/games/popular");
            if (platform != null && !platform.isBlank()) {
                builder.queryParam("platform", platform);
            }
            ResponseEntity<GameSearchDTO> response = restTemplate.exchange(
                    builder.toUriString(),
                    HttpMethod.GET,
                    buildRequest(bearerToken),
                    GameSearchDTO.class
            );
            GameSearchDTO body = response.getBody();
            if (body == null || body.getGames() == null) {
                return Collections.emptyList();
            }
            return body.getGames();
        } catch (RestClientException ex) {
            String statusHint = (ex instanceof HttpClientErrorException hce) ? " " + hce.getStatusCode() : "";
            log.warn("Failed to fetch popular games from Game Service: {}{}", ex.getClass().getSimpleName(), statusHint);
            return Collections.emptyList();
        }
    }

    // Pre-computed similar games. game-service serves top-N from game_similarities.
    public List<GameDTO> getSimilarGames(Integer igdbId, int limit, String bearerToken) {
        try {
            java.net.URI uri = UriComponentsBuilder
                    .fromUriString(gameServiceUrl + "/api/v1/games/" + igdbId + "/similar")
                    .queryParam("limit", limit)
                    .build().encode().toUri();
            ResponseEntity<GameDTO[]> response = restTemplate.exchange(
                    uri, HttpMethod.GET, buildRequest(bearerToken), GameDTO[].class);
            if (response.getBody() == null) return Collections.emptyList();
            return java.util.Arrays.asList(response.getBody());
        } catch (RestClientException ex) {
            log.warn("Failed to fetch similar games for {}: {}", igdbId, ex.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    public List<GameDTO> getRandomFromCache(int limit, String bearerToken) {
        try {
            String url = UriComponentsBuilder
                    .fromUriString(gameServiceUrl + "/api/v1/games/random")
                    .queryParam("limit", limit)
                    .toUriString();
            ResponseEntity<GameSearchDTO> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    buildRequest(bearerToken),
                    GameSearchDTO.class
            );
            GameSearchDTO body = response.getBody();
            if (body == null || body.getGames() == null) {
                return Collections.emptyList();
            }
            return body.getGames();
        } catch (RestClientException ex) {
            String statusHint = (ex instanceof HttpClientErrorException hce) ? " " + hce.getStatusCode() : "";
            log.warn("Failed to fetch random games from cache: {}{}", ex.getClass().getSimpleName(), statusHint);
            return Collections.emptyList();
        }
    }

    private HttpEntity<Void> buildRequest(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            headers.set("X-Request-ID", requestId);
        }
        return new HttpEntity<>(headers);
    }
}
