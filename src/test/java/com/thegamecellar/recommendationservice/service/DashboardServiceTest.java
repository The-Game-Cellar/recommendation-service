package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.exception.ServiceCommunicationException;
import com.thegamecellar.recommendationservice.model.dto.BecauseYouLikedDTO;
import com.thegamecellar.recommendationservice.model.dto.DashboardDTO;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private RecommendationService recommendationService;

    @Mock
    private WildCardService wildCardService;

    @Mock
    private SimilarGameService similarGameService;

    @Mock
    private LibraryServiceClient libraryServiceClient;

    @InjectMocks
    private DashboardService dashboardService;

    @Test
    void getDashboard_returns_all_three_sections() {
        when(recommendationService.getPersonalized(eq("token"), eq(10), isNull())).thenReturn(List.of(reco("Game A")));
        when(wildCardService.getWildCard("token", 5)).thenReturn(List.of(reco("Wild Game")));
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());

        DashboardDTO result = dashboardService.getDashboard("token");

        assertThat(result.getRecommendations()).hasSize(1);
        assertThat(result.getWildcard()).hasSize(1);
        assertThat(result.getBecauseYouLiked()).isEmpty();
    }

    @Test
    void getDashboard_returns_empty_because_you_liked_when_no_high_rated_games() {
        when(recommendationService.getPersonalized(eq("token"), eq(10), isNull())).thenReturn(List.of());
        when(wildCardService.getWildCard("token", 5)).thenReturn(List.of());
        when(libraryServiceClient.getGames("token")).thenReturn(List.of(lowRatedGame(1, 5)));

        DashboardDTO result = dashboardService.getDashboard("token");

        assertThat(result.getBecauseYouLiked()).isEmpty();
    }

    @Test
    void getDashboard_populates_because_you_liked_from_high_rated_seeds() {
        when(recommendationService.getPersonalized(eq("token"), eq(10), isNull())).thenReturn(List.of());
        when(wildCardService.getWildCard("token", 5)).thenReturn(List.of());
        when(libraryServiceClient.getGames("token")).thenReturn(List.of(highRatedGame(1, "The Witcher 3", 9)));
        when(similarGameService.getBecauseYouLiked(eq(1), eq("token"), anyInt()))
                .thenReturn(List.of(reco("Dragon Age")));

        DashboardDTO result = dashboardService.getDashboard("token");

        assertThat(result.getBecauseYouLiked()).hasSize(1);
        BecauseYouLikedDTO section = result.getBecauseYouLiked().get(0);
        assertThat(section.getBasedOnGame()).isEqualTo("The Witcher 3");
        assertThat(section.getRecommendations()).hasSize(1);
    }

    @Test
    void getDashboard_skips_failed_seed_instead_of_crashing() {
        // Only 1 seed is picked (limit 1 after shuffle). If that seed's similar-game call
        // throws, becauseYouLiked must be empty, not an exception.
        when(recommendationService.getPersonalized(eq("token"), eq(10), isNull())).thenReturn(List.of());
        when(wildCardService.getWildCard("token", 5)).thenReturn(List.of());
        when(libraryServiceClient.getGames("token")).thenReturn(List.of(
                highRatedGame(1, "Witcher 3", 9)
        ));
        when(similarGameService.getBecauseYouLiked(eq(1), anyString(), anyInt()))
                .thenThrow(new ServiceCommunicationException("Game Service unavailable", null));

        DashboardDTO result = dashboardService.getDashboard("token");

        assertThat(result.getBecauseYouLiked()).isEmpty();
    }

    @Test
    void getDashboard_returns_partial_dashboard_when_personalized_fails() {
        when(recommendationService.getPersonalized(eq("token"), eq(10), isNull()))
                .thenThrow(new ServiceCommunicationException("Game Service unavailable", null));
        when(wildCardService.getWildCard("token", 5)).thenReturn(List.of(reco("Wild Game")));
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());

        DashboardDTO result = dashboardService.getDashboard("token");

        assertThat(result.getRecommendations()).isEmpty();
        assertThat(result.getWildcard()).hasSize(1);
    }

    @Test
    void getDashboard_returns_partial_dashboard_when_wildcard_fails() {
        when(recommendationService.getPersonalized(eq("token"), eq(10), isNull())).thenReturn(List.of(reco("Game A")));
        when(wildCardService.getWildCard("token", 5))
                .thenThrow(new ServiceCommunicationException("Game Service unavailable", null));
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());

        DashboardDTO result = dashboardService.getDashboard("token");

        assertThat(result.getRecommendations()).hasSize(1);
        assertThat(result.getWildcard()).isEmpty();
    }

    @Test
    void getDashboard_excludes_seeds_with_null_igdb_id() {
        when(recommendationService.getPersonalized(eq("token"), eq(10), isNull())).thenReturn(List.of());
        when(wildCardService.getWildCard("token", 5)).thenReturn(List.of());

        UserGameDTO gameWithNullId = new UserGameDTO();
        gameWithNullId.setIgdbGameId(null);
        gameWithNullId.setRating(9);
        gameWithNullId.setGameName("Broken Game");

        when(libraryServiceClient.getGames("token")).thenReturn(List.of(gameWithNullId));

        DashboardDTO result = dashboardService.getDashboard("token");

        assertThat(result.getBecauseYouLiked()).isEmpty();
    }

    @Test
    void getDashboard_forwards_recently_shown_ids_to_personalized_section() {
        Set<Integer> recent = Set.of(101, 202, 303);
        when(recommendationService.getPersonalized(eq("token"), eq(10), eq(recent))).thenReturn(List.of(reco("Game A")));
        when(wildCardService.getWildCard("token", 5)).thenReturn(List.of());
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());

        DashboardDTO result = dashboardService.getDashboard("token", recent);

        assertThat(result.getRecommendations()).hasSize(1);
        verify(recommendationService).getPersonalized(eq("token"), eq(10), eq(recent));
    }

    private RecommendationDTO reco(String name) {
        return RecommendationDTO.builder()
                .igdbId(1)
                .name(name)
                .rating(BigDecimal.valueOf(4.0))
                .build();
    }

    private UserGameDTO highRatedGame(int rawgId, String name, int rating) {
        UserGameDTO game = new UserGameDTO();
        game.setIgdbGameId(rawgId);
        game.setGameName(name);
        game.setRating(rating);
        return game;
    }

    private UserGameDTO lowRatedGame(int rawgId, int rating) {
        UserGameDTO game = new UserGameDTO();
        game.setIgdbGameId(rawgId);
        game.setRating(rating);
        return game;
    }
}