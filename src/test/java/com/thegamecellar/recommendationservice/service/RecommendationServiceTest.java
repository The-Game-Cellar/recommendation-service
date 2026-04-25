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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private GameServiceClient gameServiceClient;

    @Mock
    private LibraryServiceClient libraryServiceClient;

    @InjectMocks
    private RecommendationService recommendationService;

    // --- Tier 3: new user, no rated games ---

    @Test
    void getPersonalized_returns_tier3_for_new_user_with_no_rated_games() {
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.getPopularGames("PC")).thenReturn(List.of(game(1, "Popular Game")));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTier()).isEqualTo(3);
        assertThat(result.get(0).getReason()).isEqualTo("Popular on your platforms");
    }

    @Test
    void getPersonalized_tier3_falls_back_to_global_popular_when_no_platforms() {
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of());
        when(gameServiceClient.getPopularGames(null)).thenReturn(List.of(game(1, "Global Popular")));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result).hasSize(1);
    }

    // --- Tier 2: 1-4 rated games ---

    @Test
    void getPersonalized_returns_tier2_for_user_with_few_rated_games() {
        UserGameDTO rated = ratedGame(1, 8);
        when(libraryServiceClient.getGames("token")).thenReturn(List.of(rated));
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.getGameById(1)).thenReturn(gameWithGenres(1, "RPG"));
        when(gameServiceClient.searchByGenre(eq("RPG"), isNull(), anyInt())).thenReturn(List.of(game(2, "Popular RPG", "RPG")));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result.get(0).getTier()).isEqualTo(2);
        assertThat(result.get(0).getReason()).isEqualTo("Popular in your genres");
    }

    // --- Tier 1: 5+ rated games ---

    @Test
    void getPersonalized_returns_tier1_for_user_with_5_or_more_rated_games() {
        List<UserGameDTO> ratedGames = List.of(
                ratedGame(1, 9), ratedGame(2, 8), ratedGame(3, 7), ratedGame(4, 9), ratedGame(5, 8)
        );
        when(libraryServiceClient.getGames("token")).thenReturn(ratedGames);
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));

        GameDTO rpgDetails = gameWithGenres(1, "RPG");
        when(gameServiceClient.getGameById(1)).thenReturn(rpgDetails);
        when(gameServiceClient.getGameById(2)).thenReturn(gameWithGenres(2, "RPG"));
        when(gameServiceClient.getGameById(3)).thenReturn(gameWithGenres(3, "RPG"));
        when(gameServiceClient.getGameById(4)).thenReturn(gameWithGenres(4, "RPG"));
        when(gameServiceClient.getGameById(5)).thenReturn(gameWithGenres(5, "RPG"));
        when(gameServiceClient.searchByGenre(eq("RPG"), isNull(), anyInt())).thenReturn(List.of(game(6, "New RPG", "RPG")));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result.get(0).getTier()).isEqualTo(1);
        assertThat(result.get(0).getReason()).isEqualTo("Based on your ratings");
    }

    // --- Collection exclusion ---

    @Test
    void getPersonalized_excludes_games_already_in_collection() {
        when(libraryServiceClient.getGames("token")).thenReturn(List.of(ownedGame(1)));
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.getPopularGames("PC")).thenReturn(
                List.of(game(1, "Owned Game"), game(2, "New Game"))
        );

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result).noneMatch(r -> r.getIgdbId() == 1);
        assertThat(result).anyMatch(r -> r.getIgdbId() == 2);
    }

    // --- Library service down ---

    @Test
    void getPersonalized_returns_empty_when_library_service_is_down() {
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        // getPlatforms and getPopularGames not mocked — Mockito returns empty list by default

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result).isEmpty();
    }

    // --- Helpers ---

    private UserGameDTO ratedGame(int rawgId, int rating) {
        UserGameDTO game = new UserGameDTO();
        game.setIgdbGameId(rawgId);
        game.setRating(rating);
        return game;
    }

    private UserGameDTO ownedGame(int rawgId) {
        UserGameDTO game = new UserGameDTO();
        game.setIgdbGameId(rawgId);
        return game;
    }

    private UserPlatformDTO platform(String name) {
        UserPlatformDTO p = new UserPlatformDTO();
        p.setPlatformName(name);
        return p;
    }

    private GameDTO game(int rawgId, String name, String... genres) {
        GameDTO game = new GameDTO();
        game.setIgdbId(rawgId);
        game.setName(name);
        game.setRating(BigDecimal.valueOf(4.0));
        game.setPlatforms(List.of("PC"));
        if (genres.length > 0) {
            game.setGenres(List.of(genres));
        }
        return game;
    }

    private GameDTO gameWithGenres(int rawgId, String... genres) {
        GameDTO game = new GameDTO();
        game.setIgdbId(rawgId);
        game.setGenres(List.of(genres));
        return game;
    }
}