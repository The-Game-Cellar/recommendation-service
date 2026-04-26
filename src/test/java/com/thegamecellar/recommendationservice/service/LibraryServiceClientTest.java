package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.exception.ServiceCommunicationException;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibraryServiceClientTest {

    @Mock
    private RestTemplate restTemplate;

    private LibraryServiceClient client;

    @BeforeEach
    void setUp() {
        client = new LibraryServiceClient(restTemplate);
        ReflectionTestUtils.setField(client, "libraryServiceUrl", "http://library-service");
    }

    // --- getGames ---

    @Test
    void getGames_returns_list_on_success() {
        UserGameDTO game = new UserGameDTO();
        game.setIgdbGameId(1);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(UserGameDTO[].class)))
                .thenReturn(ResponseEntity.ok(new UserGameDTO[]{game}));

        List<UserGameDTO> result = client.getGames("Bearer token");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getIgdbGameId()).isEqualTo(1);
    }

    @Test
    void getGames_returns_empty_when_body_is_null() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(UserGameDTO[].class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThat(client.getGames("Bearer token")).isEmpty();
    }

    @Test
    void getGames_throws_ServiceCommunicationException_on_401() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(UserGameDTO[].class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.getGames("Bearer expired"))
                .isInstanceOf(ServiceCommunicationException.class)
                .hasMessageContaining("401");
    }

    @Test
    void getGames_throws_ServiceCommunicationException_on_403() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(UserGameDTO[].class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.getGames("Bearer token"))
                .isInstanceOf(ServiceCommunicationException.class)
                .hasMessageContaining("403");
    }

    @Test
    void getGames_returns_empty_on_other_client_error() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(UserGameDTO[].class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        assertThat(client.getGames("Bearer token")).isEmpty();
    }

    @Test
    void getGames_returns_empty_when_library_service_is_down() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(UserGameDTO[].class)))
                .thenThrow(new RestClientException("Connection refused"));

        assertThat(client.getGames("Bearer token")).isEmpty();
    }

    // --- getPlatforms ---

    @Test
    void getPlatforms_returns_list_on_success() {
        UserPlatformDTO platform = new UserPlatformDTO();
        platform.setPlatformName("PC");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(UserPlatformDTO[].class)))
                .thenReturn(ResponseEntity.ok(new UserPlatformDTO[]{platform}));

        List<UserPlatformDTO> result = client.getPlatforms("Bearer token");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPlatformName()).isEqualTo("PC");
    }

    @Test
    void getPlatforms_throws_ServiceCommunicationException_on_401() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(UserPlatformDTO[].class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.getPlatforms("Bearer expired"))
                .isInstanceOf(ServiceCommunicationException.class)
                .hasMessageContaining("401");
    }

    @Test
    void getPlatforms_throws_ServiceCommunicationException_on_403() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(UserPlatformDTO[].class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.getPlatforms("Bearer token"))
                .isInstanceOf(ServiceCommunicationException.class)
                .hasMessageContaining("403");
    }

    @Test
    void getPlatforms_returns_empty_when_library_service_is_down() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(UserPlatformDTO[].class)))
                .thenThrow(new RestClientException("Connection refused"));

        assertThat(client.getPlatforms("Bearer token")).isEmpty();
    }
}
