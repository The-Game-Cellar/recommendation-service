package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.GameServiceClient;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WildCardServiceTest {

    @Mock private GameServiceClient gameServiceClient;
    @Mock private UserStateCache userStateCache;

    private WildCardService wildCardService;

    @BeforeEach
    void setUp() {
        wildCardService = new WildCardService(gameServiceClient, userStateCache);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getWildCard_returnsPicks_unauthenticated_caller_skipsCache() {
        // No JwtAuthenticationToken in SecurityContext -> currentUserId() returns null -> empty
        // owned + platforms -> matchesAnyPlatform short-circuits true for every candidate.
        when(gameServiceClient.getRandomFromCache(anyInt(), anyString()))
                .thenReturn(List.of(game(1, "First"), game(2, "Second"), game(3, "Third")));

        List<RecommendationDTO> result = wildCardService.getWildCard("token", 3);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(RecommendationDTO::getIgdbId).containsExactly(1, 2, 3);
    }

    @Test
    void getWildCard_emptyCatalog_returnsEmpty() {
        when(gameServiceClient.getRandomFromCache(anyInt(), anyString())).thenReturn(List.of());

        List<RecommendationDTO> result = wildCardService.getWildCard("token", 10);

        assertThat(result).isEmpty();
    }

    @Test
    void getWildCard_setsReason() {
        when(gameServiceClient.getRandomFromCache(anyInt(), anyString()))
                .thenReturn(List.of(game(1, "Some Game")));

        List<RecommendationDTO> result = wildCardService.getWildCard("token", 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getReason()).isEqualTo("Wild Card - something different");
    }

    @Test
    void getWildCard_filtersNullIds() {
        GameDTO valid = game(1, "Valid");
        GameDTO nullId = new GameDTO();
        nullId.setName("No id");
        nullId.setPlatforms(List.of("PC"));
        when(gameServiceClient.getRandomFromCache(anyInt(), anyString()))
                .thenReturn(List.of(valid, nullId));

        List<RecommendationDTO> result = wildCardService.getWildCard("token", 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getIgdbId()).isEqualTo(1);
    }

    private GameDTO game(int igdbId, String name) {
        GameDTO g = new GameDTO();
        g.setIgdbId(igdbId);
        g.setName(name);
        g.setRating(BigDecimal.valueOf(8.0));
        g.setPlatforms(List.of("PC"));
        return g;
    }
}
