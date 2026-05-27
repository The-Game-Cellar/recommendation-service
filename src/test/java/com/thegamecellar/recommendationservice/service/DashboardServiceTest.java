package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.exception.ServiceCommunicationException;
import com.thegamecellar.recommendationservice.model.dto.BecauseYouLikedDTO;
import com.thegamecellar.recommendationservice.model.dto.DashboardDTO;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final String UID = "u1";
    private static final String TOK = "token";

    @Mock private RecommendationService recommendationService;
    @Mock private WildCardService wildCardService;
    @Mock private SimilarGameService similarGameService;
    @Mock private LibraryServiceClient libraryServiceClient;
    @Mock private com.thegamecellar.recommendationservice.repository.UserCandidatePoolRepository poolRepository;

    private ExecutorService dashboardExecutor;
    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardExecutor = Executors.newFixedThreadPool(6);
        dashboardService = new DashboardService(recommendationService, wildCardService,
                similarGameService, libraryServiceClient, poolRepository, dashboardExecutor);
    }

    @AfterEach
    void tearDown() {
        if (dashboardExecutor != null) dashboardExecutor.shutdownNow();
    }

    @Test
    void getDashboard_returnsAllThreeSections() {
        when(recommendationService.getPersonalized(eq(UID), eq(TOK), eq(20), isNull()))
                .thenReturn(List.of(reco("Game A")));
        when(wildCardService.getWildCard(TOK, 12)).thenReturn(List.of(reco("Wild Game")));
        when(libraryServiceClient.getGames(TOK)).thenReturn(List.of());

        DashboardDTO result = dashboardService.getDashboard(UID, TOK, null);

        assertThat(result.getRecommendations()).hasSize(1);
        assertThat(result.getWildcard()).hasSize(1);
        assertThat(result.getBecauseYouLiked()).isEmpty();
    }

    @Test
    void getDashboard_emptyBecauseYouLikedWhenNoHighRated() {
        when(recommendationService.getPersonalized(eq(UID), eq(TOK), eq(20), isNull())).thenReturn(List.of());
        when(wildCardService.getWildCard(TOK, 12)).thenReturn(List.of());
        when(libraryServiceClient.getGames(TOK)).thenReturn(List.of(lowRatedGame(1, 5)));

        DashboardDTO result = dashboardService.getDashboard(UID, TOK, null);

        assertThat(result.getBecauseYouLiked()).isEmpty();
    }

    @Test
    void getDashboard_populatesBecauseYouLikedFromHighRatedSeed() {
        when(recommendationService.getPersonalized(eq(UID), eq(TOK), eq(20), isNull())).thenReturn(List.of());
        when(wildCardService.getWildCard(TOK, 12)).thenReturn(List.of());
        when(libraryServiceClient.getGames(TOK)).thenReturn(List.of(highRatedGame(1, "The Witcher 3", 9)));
        when(similarGameService.getBecauseYouLiked(eq(1), eq(TOK), anyInt()))
                .thenReturn(List.of(reco("Dragon Age")));

        DashboardDTO result = dashboardService.getDashboard(UID, TOK, null);

        assertThat(result.getBecauseYouLiked()).hasSize(1);
        BecauseYouLikedDTO section = result.getBecauseYouLiked().get(0);
        assertThat(section.getBasedOnGame()).isEqualTo("The Witcher 3");
        assertThat(section.getRecommendations()).hasSize(1);
    }

    @Test
    void getDashboard_skipsFailedSeedInsteadOfCrashing() {
        when(recommendationService.getPersonalized(eq(UID), eq(TOK), eq(20), isNull())).thenReturn(List.of());
        when(wildCardService.getWildCard(TOK, 12)).thenReturn(List.of());
        when(libraryServiceClient.getGames(TOK)).thenReturn(List.of(highRatedGame(1, "Witcher 3", 9)));
        when(similarGameService.getBecauseYouLiked(eq(1), anyString(), anyInt()))
                .thenThrow(new ServiceCommunicationException("Game Service unavailable", null));

        DashboardDTO result = dashboardService.getDashboard(UID, TOK, null);

        assertThat(result.getBecauseYouLiked()).isEmpty();
    }

    @Test
    void getDashboard_partialWhenPersonalizedFails() {
        when(recommendationService.getPersonalized(eq(UID), eq(TOK), eq(20), isNull()))
                .thenThrow(new ServiceCommunicationException("Game Service unavailable", null));
        when(wildCardService.getWildCard(TOK, 12)).thenReturn(List.of(reco("Wild Game")));
        when(libraryServiceClient.getGames(TOK)).thenReturn(List.of());

        DashboardDTO result = dashboardService.getDashboard(UID, TOK, null);

        assertThat(result.getRecommendations()).isEmpty();
        assertThat(result.getWildcard()).hasSize(1);
    }

    @Test
    void getDashboard_partialWhenWildcardFails() {
        when(recommendationService.getPersonalized(eq(UID), eq(TOK), eq(20), isNull())).thenReturn(List.of(reco("Game A")));
        when(wildCardService.getWildCard(TOK, 12))
                .thenThrow(new ServiceCommunicationException("Game Service unavailable", null));
        when(libraryServiceClient.getGames(TOK)).thenReturn(List.of());

        DashboardDTO result = dashboardService.getDashboard(UID, TOK, null);

        assertThat(result.getRecommendations()).hasSize(1);
        assertThat(result.getWildcard()).isEmpty();
    }

    @Test
    void getDashboard_excludesSeedsWithNullIgdbId() {
        when(recommendationService.getPersonalized(eq(UID), eq(TOK), eq(20), isNull())).thenReturn(List.of());
        when(wildCardService.getWildCard(TOK, 12)).thenReturn(List.of());

        UserGameDTO gameWithNullId = new UserGameDTO();
        gameWithNullId.setIgdbGameId(null);
        gameWithNullId.setRating(9);
        gameWithNullId.setGameName("Broken Game");

        when(libraryServiceClient.getGames(TOK)).thenReturn(List.of(gameWithNullId));

        DashboardDTO result = dashboardService.getDashboard(UID, TOK, null);

        assertThat(result.getBecauseYouLiked()).isEmpty();
    }

    @Test
    void getDashboard_forwardsRecentlyShownIdsToPersonalized() {
        Set<Integer> recent = Set.of(101, 202, 303);
        when(recommendationService.getPersonalized(eq(UID), eq(TOK), eq(20), eq(recent))).thenReturn(List.of(reco("Game A")));
        when(wildCardService.getWildCard(TOK, 12)).thenReturn(List.of());
        when(libraryServiceClient.getGames(TOK)).thenReturn(List.of());

        DashboardDTO result = dashboardService.getDashboard(UID, TOK, recent);

        assertThat(result.getRecommendations()).hasSize(1);
        verify(recommendationService).getPersonalized(eq(UID), eq(TOK), eq(20), eq(recent));
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
