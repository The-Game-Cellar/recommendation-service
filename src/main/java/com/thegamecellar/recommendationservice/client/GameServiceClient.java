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
            String url = gameServiceUrl + "/api/v1/games/popular";
            if (platform != null && !platform.isBlank()) {
                url += "?platform=" + platform;
            }
            GameSearchDTO response = restTemplate.getForObject(url, GameSearchDTO.class);
            if (response == null || response.getGames() == null) {
                return Collections.emptyList();
            }
            return response.getGames();
        } catch (RestClientException ex) {
            log.warn("Failed to fetch popular games from Game Service: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    public List<GameDTO> searchByGenre(String genre, String platform) {
        try {
            StringBuilder url = new StringBuilder(gameServiceUrl + "/api/v1/games/search?pageSize=40");
            if (genre != null && !genre.isBlank()) {
                url.append("&genre=").append(genre);
            }
            if (platform != null && !platform.isBlank()) {
                url.append("&platform=").append(platform);
            }
            GameSearchDTO response = restTemplate.getForObject(url.toString(), GameSearchDTO.class);
            if (response == null || response.getGames() == null) {
                return Collections.emptyList();
            }
            return response.getGames();
        } catch (RestClientException ex) {
            log.warn("Failed to search games by genre '{}' from Game Service: {}", genre, ex.getMessage());
            return Collections.emptyList();
        }
    }
}
