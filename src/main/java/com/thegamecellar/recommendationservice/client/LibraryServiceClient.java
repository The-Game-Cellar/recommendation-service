package com.thegamecellar.recommendationservice.client;

import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryServiceClient {

    private final RestTemplate restTemplate;

    @Value("${services.library-service.url}")
    private String libraryServiceUrl;

    public List<UserGameDTO> getGames(String bearerToken) {
        try {
            ResponseEntity<UserGameDTO[]> response = restTemplate.exchange(
                    libraryServiceUrl + "/api/v1/library/games",
                    HttpMethod.GET,
                    buildRequest(bearerToken),
                    UserGameDTO[].class
            );
            if (response.getBody() == null) {
                return Collections.emptyList();
            }
            return Arrays.asList(response.getBody());
        } catch (RestClientException ex) {
            log.warn("Failed to fetch games from Library Service: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    public List<UserPlatformDTO> getPlatforms(String bearerToken) {
        try {
            ResponseEntity<UserPlatformDTO[]> response = restTemplate.exchange(
                    libraryServiceUrl + "/api/v1/library/platforms",
                    HttpMethod.GET,
                    buildRequest(bearerToken),
                    UserPlatformDTO[].class
            );
            if (response.getBody() == null) {
                return Collections.emptyList();
            }
            return Arrays.asList(response.getBody());
        } catch (RestClientException ex) {
            log.warn("Failed to fetch platforms from Library Service: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    private HttpEntity<Void> buildRequest(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        return new HttpEntity<>(headers);
    }
}
