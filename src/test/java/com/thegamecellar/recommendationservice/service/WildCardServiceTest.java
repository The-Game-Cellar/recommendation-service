package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.GameServiceClient;
import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.exception.ServiceCommunicationException;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WildCardServiceTest {

    @Mock
    private GameServiceClient gameServiceClient;

    @Mock
    private LibraryServiceClient libraryServiceClient;

    @InjectMocks
    private WildCardService wildCardService;

    @Test
    void getWildCard_excludes_games_already_in_collection() {
        when(libraryServiceClient.getGames("token")).thenReturn(List.of(ownedGame(1)));
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.getRandomGames(anyString(), anyInt())).thenReturn(List.of(game(1, "Owned Game"), game(2, "New Game")));

        List<RecommendationDTO> result = wildCardService.getWildCard("token", 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("New Game");
    }

    @Test
    void getWildCard_fetches_global_popular_when_user_has_no_platforms() {
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of());
        when(gameServiceClient.getRandomGames(isNull(), anyInt())).thenReturn(List.of(game(1, "Popular Game")));

        List<RecommendationDTO> result = wildCardService.getWildCard("token", 10);

        assertThat(result).hasSize(1);
    }

    @Test
    void getWildCard_respects_limit() {
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.getRandomGames(anyString(), anyInt())).thenReturn(
                List.of(game(1, "A"), game(2, "B"), game(3, "C"), game(4, "D"), game(5, "E"))
        );

        List<RecommendationDTO> result = wildCardService.getWildCard("token", 3);

        assertThat(result).hasSize(3);
    }

    @Test
    void getWildCard_throws_when_library_service_is_down() {
        when(libraryServiceClient.getGames("token"))
                .thenThrow(new ServiceCommunicationException("Library Service unavailable", null));

        assertThatThrownBy(() -> wildCardService.getWildCard("token", 10))
                .isInstanceOf(ServiceCommunicationException.class);
    }

    @Test
    void getWildCard_returns_wildcard_reason() {
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of());
        when(gameServiceClient.getRandomGames(isNull(), anyInt())).thenReturn(List.of(game(1, "Some Game")));

        List<RecommendationDTO> result = wildCardService.getWildCard("token", 10);

        assertThat(result.get(0).getReason()).isEqualTo("Wild Card - something different");
    }

    private GameDTO game(int rawgId, String name) {
        GameDTO game = new GameDTO();
        game.setRawgId(rawgId);
        game.setName(name);
        game.setRating(BigDecimal.valueOf(4.0));
        game.setPlatforms(List.of("PC"));
        return game;
    }

    private UserGameDTO ownedGame(int rawgId) {
        UserGameDTO game = new UserGameDTO();
        game.setRawgGameId(rawgId);
        return game;
    }

    private UserPlatformDTO platform(String name) {
        UserPlatformDTO p = new UserPlatformDTO();
        p.setPlatformName(name);
        return p;
    }
}