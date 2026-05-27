package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.GameServiceClient;
import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimilarGameServiceTest {

    @Mock private GameServiceClient gameServiceClient;
    @Mock private LibraryServiceClient libraryServiceClient;
    @Mock private UserStateCache userStateCache;

    private SimilarGameService similarGameService;

    @BeforeEach
    void setUp() {
        similarGameService = new SimilarGameService(
                gameServiceClient, libraryServiceClient, userStateCache);
    }

    @Test
    void getSimilar_returns_empty_when_source_game_not_found() {
        when(gameServiceClient.getGameById(eq(999), anyString())).thenReturn(null);

        List<RecommendationDTO> result = similarGameService.getSimilar(999, "token", 10);

        assertThat(result).isEmpty();
    }

    @Test
    void getSimilar_proxies_game_service_similar_endpoint() {
        when(gameServiceClient.getGameById(eq(1), anyString())).thenReturn(sourceGame(1, "The Witcher 3", "RPG"));
        when(gameServiceClient.getSimilarGames(eq(1), anyInt(), anyString())).thenReturn(
                List.of(game(2, "Dragon Age", "RPG"), game(3, "Skyrim", "RPG"))
        );

        List<RecommendationDTO> result = similarGameService.getSimilar(1, "token", 10);

        assertThat(result).extracting(RecommendationDTO::getIgdbId).containsExactly(2, 3);
    }

    @Test
    void getSimilar_excludes_source_game_from_results() {
        when(gameServiceClient.getGameById(eq(1), anyString())).thenReturn(sourceGame(1, "The Witcher 3", "RPG"));
        when(gameServiceClient.getSimilarGames(eq(1), anyInt(), anyString())).thenReturn(
                List.of(game(1, "The Witcher 3", "RPG"), game(2, "Dragon Age", "RPG"))
        );

        List<RecommendationDTO> result = similarGameService.getSimilar(1, "token", 10);

        assertThat(result).noneMatch(r -> r.getIgdbId() == 1);
    }

    @Test
    void getSimilar_reason_prefix() {
        when(gameServiceClient.getGameById(eq(1), anyString())).thenReturn(sourceGame(1, "The Witcher 3", "RPG"));
        when(gameServiceClient.getSimilarGames(eq(1), anyInt(), anyString()))
                .thenReturn(List.of(game(2, "Dragon Age", "RPG")));

        List<RecommendationDTO> result = similarGameService.getSimilar(1, "token", 10);

        assertThat(result.get(0).getReason()).isEqualTo("Similar to The Witcher 3");
    }

    @Test
    void getBecauseYouLiked_filters_owned_games() {
        when(gameServiceClient.getGameById(eq(1), anyString())).thenReturn(sourceGame(1, "The Witcher 3", "RPG"));
        when(libraryServiceClient.getGames("token")).thenReturn(List.of(ownedGame(2)));
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of());
        when(gameServiceClient.getSimilarGames(eq(1), anyInt(), anyString())).thenReturn(
                List.of(game(2, "Owned RPG", "RPG"), game(3, "Free RPG", "RPG"))
        );

        List<RecommendationDTO> result = similarGameService.getBecauseYouLiked(1, "token", 10);

        assertThat(result).noneMatch(r -> r.getIgdbId() == 2);
        assertThat(result).anyMatch(r -> r.getIgdbId() == 3);
    }

    @Test
    void getBecauseYouLiked_filters_by_user_platforms() {
        when(gameServiceClient.getGameById(eq(1), anyString())).thenReturn(sourceGame(1, "The Witcher 3", "RPG"));
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.getSimilarGames(eq(1), anyInt(), anyString())).thenReturn(
                List.of(gameOnPlatform(2, "PC Game", "RPG", "PC"),
                        gameOnPlatform(3, "Console Game", "RPG", "PlayStation 4"))
        );

        List<RecommendationDTO> result = similarGameService.getBecauseYouLiked(1, "token", 10);

        assertThat(result).anyMatch(r -> r.getIgdbId() == 2);
        assertThat(result).noneMatch(r -> r.getIgdbId() == 3);
    }

    @Test
    void getBecauseYouLiked_reason_prefix() {
        when(gameServiceClient.getGameById(eq(1), anyString())).thenReturn(sourceGame(1, "The Witcher 3", "RPG"));
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of());
        when(gameServiceClient.getSimilarGames(eq(1), anyInt(), anyString()))
                .thenReturn(List.of(game(2, "Dragon Age", "RPG")));

        List<RecommendationDTO> result = similarGameService.getBecauseYouLiked(1, "token", 10);

        assertThat(result.get(0).getReason()).isEqualTo("Because you liked The Witcher 3");
    }

    @Test
    void getSimilar_returns_empty_when_no_pre_computed_similarities() {
        when(gameServiceClient.getGameById(eq(1), anyString())).thenReturn(sourceGame(1, "The Witcher 3", "RPG"));
        when(gameServiceClient.getSimilarGames(eq(1), anyInt(), anyString())).thenReturn(List.of());

        List<RecommendationDTO> result = similarGameService.getSimilar(1, "token", 10);

        assertThat(result).isEmpty();
    }

    private GameDTO sourceGame(int igdbId, String name, String... genres) {
        GameDTO game = new GameDTO();
        game.setIgdbId(igdbId);
        game.setName(name);
        game.setGenres(List.of(genres));
        return game;
    }

    private GameDTO game(int igdbId, String name, String... genres) {
        GameDTO game = new GameDTO();
        game.setIgdbId(igdbId);
        game.setName(name);
        game.setRating(BigDecimal.valueOf(8.0));
        game.setGenres(List.of(genres));
        return game;
    }

    private GameDTO gameOnPlatform(int igdbId, String name, String genre, String platform) {
        GameDTO game = game(igdbId, name, genre);
        game.setPlatforms(List.of(platform));
        return game;
    }

    private UserGameDTO ownedGame(int igdbId) {
        UserGameDTO game = new UserGameDTO();
        game.setIgdbGameId(igdbId);
        return game;
    }

    private UserPlatformDTO platform(String name) {
        UserPlatformDTO p = new UserPlatformDTO();
        p.setPlatformName(name);
        return p;
    }

    @SuppressWarnings("unused")
    private Set<Integer> nothing() { return Set.of(); }
}
