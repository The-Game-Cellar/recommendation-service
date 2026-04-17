package com.thegamecellar.recommendationservice.client;

import com.thegamecellar.recommendationservice.exception.ServiceCommunicationException;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.game.GameSearchDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
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

    public GameDTO getGameById(Integer rawgId) {
        try {
            return restTemplate.getForObject(
                    gameServiceUrl + "/api/v1/games/{rawgId}",
                    GameDTO.class,
                    rawgId
            );
        } catch (RestClientException ex) {
            log.warn("Failed to fetch game {} from Game Service: {}", rawgId, ex.getMessage());
            throw new ServiceCommunicationException("Game Service unavailable", ex);
        }
    }

    public List<GameDTO> getPopularGames(String platform) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromUriString(gameServiceUrl + "/api/v1/games/popular");
            if (platform != null && !platform.isBlank()) {
                builder.queryParam("platform", platform);
            }
            GameSearchDTO response = restTemplate.getForObject(builder.toUriString(), GameSearchDTO.class);
            if (response == null || response.getGames() == null) {
                return Collections.emptyList();
            }
            return response.getGames();
        } catch (RestClientException ex) {
            log.warn("Failed to fetch popular games from Game Service: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    public List<GameDTO> searchByGenre(String genre, String platform, int page) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromUriString(gameServiceUrl + "/api/v1/games/search")
                    .queryParam("pageSize", 40)
                    .queryParam("page", page);
            if (genre != null && !genre.isBlank()) {
                builder.queryParam("genre", genre);
            }
            if (platform != null && !platform.isBlank()) {
                builder.queryParam("platform", platform);
            }
            GameSearchDTO response = restTemplate.getForObject(builder.toUriString(), GameSearchDTO.class);
            if (response == null || response.getGames() == null) {
                return Collections.emptyList();
            }
            return response.getGames();
        } catch (RestClientException ex) {
            log.warn("Failed to search games by genre '{}' from Game Service: {}", genre, ex.getMessage());
            return Collections.emptyList();
        }
    }

    public List<GameDTO> getRandomFromCache(int limit) {
        try {
            String url = UriComponentsBuilder
                    .fromUriString(gameServiceUrl + "/api/v1/games/random")
                    .queryParam("limit", limit)
                    .toUriString();
            GameSearchDTO response = restTemplate.getForObject(url, GameSearchDTO.class);
            if (response == null || response.getGames() == null) {
                return Collections.emptyList();
            }
            return response.getGames();
        } catch (RestClientException ex) {
            log.warn("Failed to fetch random games from cache: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }
}