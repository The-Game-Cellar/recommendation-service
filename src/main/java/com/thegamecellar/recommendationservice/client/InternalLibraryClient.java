package com.thegamecellar.recommendationservice.client;

import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGenrePreferenceDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserReleaseYearPreferenceDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserTagPreferenceDTO;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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

// Used by PerUserWorker which runs scheduled without a user JWT. Hits /internal/library/users/{userId}/*
// in library-service, authenticated via the shared INTERNAL_SERVICE_TOKEN header.
@Slf4j
@Component
public class InternalLibraryClient {

    private final RestTemplate restTemplate;
    private final String libraryServiceUrl;
    private final String internalToken;

    public InternalLibraryClient(RestTemplate restTemplate,
                                 @Value("${services.library-service.url}") String libraryServiceUrl,
                                 @Value("${security.internal.token:}") String internalToken) {
        this.restTemplate = restTemplate;
        this.libraryServiceUrl = libraryServiceUrl;
        this.internalToken = internalToken;
    }

    public List<UserGameDTO> getGames(String userId) {
        return fetchArray("/games", userId, UserGameDTO[].class);
    }

    public List<UserPlatformDTO> getPlatforms(String userId) {
        return fetchArray("/platforms", userId, UserPlatformDTO[].class);
    }

    public List<String> getGenrePreferences(String userId) {
        List<UserGenrePreferenceDTO> prefs = fetchArray("/preferences/genres", userId, UserGenrePreferenceDTO[].class);
        return prefs.stream()
                .map(UserGenrePreferenceDTO::getGenreName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    public List<String> getTagPreferences(String userId) {
        List<UserTagPreferenceDTO> prefs = fetchArray("/preferences/tags", userId, UserTagPreferenceDTO[].class);
        return prefs.stream()
                .map(UserTagPreferenceDTO::getTagName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    public List<String> getReleaseYearPreferences(String userId) {
        List<UserReleaseYearPreferenceDTO> prefs = fetchArray("/preferences/release-years", userId, UserReleaseYearPreferenceDTO[].class);
        return prefs.stream()
                .map(UserReleaseYearPreferenceDTO::getBucketLabel)
                .filter(label -> label != null && !label.isBlank())
                .toList();
    }

    private <T> List<T> fetchArray(String suffix, String userId, Class<T[]> type) {
        String url = libraryServiceUrl + "/internal/library/users/" + userId + suffix;
        try {
            ResponseEntity<T[]> response = restTemplate.exchange(
                    url, HttpMethod.GET, buildRequest(), type);
            if (response.getBody() == null) return Collections.emptyList();
            return Arrays.asList(response.getBody());
        } catch (RestClientException ex) {
            log.warn("Internal library-service call failed ({}): {}, returning empty list",
                    suffix, ex.getClass().getSimpleName());
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
