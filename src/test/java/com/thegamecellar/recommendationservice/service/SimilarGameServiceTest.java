package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.GameServiceClient;
import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimilarGameServiceTest {

    @Mock
    private GameServiceClient gameServiceClient;

    @Mock
    private LibraryServiceClient libraryServiceClient;

    @InjectMocks
    private SimilarGameService similarGameService;

    @Test
    void getSimilar_returns_empty_when_source_game_not_found() {
        when(gameServiceClient.getGameById(999)).thenReturn(null);

        List<RecommendationDTO> result = similarGameService.getSimilar(999, "token", 10);

        assertThat(result).isEmpty();
    }

    @Test
    void getSimilar_returns_empty_when_source_game_has_no_genres() {
        GameDTO sourceGame = new GameDTO();
        sourceGame.setRawgId(1);
        sourceGame.setGenres(List.of());
        when(gameServiceClient.getGameById(1)).thenReturn(sourceGame);

        List<RecommendationDTO> result = similarGameService.getSimilar(1, "token", 10);

        assertThat(result).isEmpty();
    }

    @Test
    void getSimilar_excludes_the_source_game_from_results() {
        when(gameServiceClient.getGameById(1)).thenReturn(sourceGame(1, "The Witcher 3", "RPG"));
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of());
        when(gameServiceClient.searchByGenre(eq("RPG"), isNull(), anyInt())).thenReturn(
                List.of(game(1, "The Witcher 3", "RPG"), game(2, "Dragon Age", "RPG"))
        );

        List<RecommendationDTO> result = similarGameService.getSimilar(1, "token", 10);

        assertThat(result).noneMatch(r -> r.getRawgId() == 1);
    }

    @Test
    void getSimilar_excludes_games_already_in_collection() {
        when(gameServiceClient.getGameById(1)).thenReturn(sourceGame(1, "The Witcher 3", "RPG"));
        when(libraryServiceClient.getGames("token")).thenReturn(List.of(ownedGame(2)));
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of());
        when(gameServiceClient.searchByGenre(eq("RPG"), isNull(), anyInt())).thenReturn(
                List.of(game(2, "Owned RPG", "RPG"), game(3, "Free RPG", "RPG"))
        );

        List<RecommendationDTO> result = similarGameService.getSimilar(1, "token", 10);

        assertThat(result).noneMatch(r -> r.getRawgId() == 2);
        assertThat(result).anyMatch(r -> r.getRawgId() == 3);
    }

    @Test
    void getSimilar_filters_by_user_platforms() {
        when(gameServiceClient.getGameById(1)).thenReturn(sourceGame(1, "The Witcher 3", "RPG"));
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.searchByGenre(eq("RPG"), isNull(), anyInt())).thenReturn(
                List.of(gameOnPlatform(2, "PC Game", "RPG", "PC"),
                        gameOnPlatform(3, "Console Game", "RPG", "PlayStation 4"))
        );

        List<RecommendationDTO> result = similarGameService.getSimilar(1, "token", 10);

        assertThat(result).anyMatch(r -> r.getRawgId() == 2);
        assertThat(result).noneMatch(r -> r.getRawgId() == 3);
    }

    @Test
    void getSimilar_uses_similar_to_reason_prefix() {
        when(gameServiceClient.getGameById(1)).thenReturn(sourceGame(1, "The Witcher 3", "RPG"));
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of());
        when(gameServiceClient.searchByGenre(eq("RPG"), isNull(), anyInt())).thenReturn(List.of(game(2, "Dragon Age", "RPG")));

        List<RecommendationDTO> result = similarGameService.getSimilar(1, "token", 10);

        assertThat(result.get(0).getReason()).isEqualTo("Similar to The Witcher 3");
    }

    @Test
    void getBecauseYouLiked_uses_because_you_liked_reason_prefix() {
        when(gameServiceClient.getGameById(1)).thenReturn(sourceGame(1, "The Witcher 3", "RPG"));
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of());
        when(gameServiceClient.searchByGenre(eq("RPG"), isNull(), anyInt())).thenReturn(List.of(game(2, "Dragon Age", "RPG")));

        List<RecommendationDTO> result = similarGameService.getBecauseYouLiked(1, "token", 10);

        assertThat(result.get(0).getReason()).isEqualTo("Because you liked The Witcher 3");
    }

    @Test
    void getSimilar_returns_empty_when_library_service_is_down() {
        when(gameServiceClient.getGameById(1)).thenReturn(sourceGame(1, "The Witcher 3", "RPG"));
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        // getPlatforms and searchByGenre not mocked — Mockito returns empty list by default

        List<RecommendationDTO> result = similarGameService.getSimilar(1, "token", 10);

        assertThat(result).isEmpty();
    }

    private GameDTO sourceGame(int rawgId, String name, String... genres) {
        GameDTO game = new GameDTO();
        game.setRawgId(rawgId);
        game.setName(name);
        game.setGenres(List.of(genres));
        return game;
    }

    private GameDTO game(int rawgId, String name, String... genres) {
        GameDTO game = new GameDTO();
        game.setRawgId(rawgId);
        game.setName(name);
        game.setRating(BigDecimal.valueOf(4.0));
        game.setGenres(List.of(genres));
        return game;
    }

    private GameDTO gameOnPlatform(int rawgId, String name, String genre, String platform) {
        GameDTO game = game(rawgId, name, genre);
        game.setPlatforms(List.of(platform));
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