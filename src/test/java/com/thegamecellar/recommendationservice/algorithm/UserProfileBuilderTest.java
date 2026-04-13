package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserProfileBuilderTest {

    @Test
    void build_returns_empty_profile_when_no_rated_games() {
        Map<String, Double> profile = UserProfileBuilder.build(List.of(), Map.of());

        assertThat(profile).isEmpty();
    }

    @Test
    void build_returns_correct_average_rating_for_genre() {
        UserGameDTO game1 = ratedGame(1, 8);
        UserGameDTO game2 = ratedGame(2, 6);
        GameDTO details1 = gameWithGenres(1, "RPG");
        GameDTO details2 = gameWithGenres(2, "RPG");

        Map<String, Double> profile = UserProfileBuilder.build(
                List.of(game1, game2),
                Map.of(1, details1, 2, details2)
        );

        assertThat(profile).containsKey("RPG");
        assertThat(profile.get("RPG")).isEqualTo(7.0); // avg of 8 and 6
    }

    @Test
    void build_skips_games_with_no_details_in_map() {
        UserGameDTO game = ratedGame(1, 9);

        Map<String, Double> profile = UserProfileBuilder.build(List.of(game), Map.of());

        assertThat(profile).isEmpty();
    }

    @Test
    void build_skips_games_with_null_genres() {
        UserGameDTO game = ratedGame(1, 9);
        GameDTO details = new GameDTO();
        details.setGenres(null);

        Map<String, Double> profile = UserProfileBuilder.build(List.of(game), Map.of(1, details));

        assertThat(profile).isEmpty();
    }

    @Test
    void build_handles_multiple_genres_per_game() {
        UserGameDTO game = ratedGame(1, 10);
        GameDTO details = gameWithGenres(1, "RPG", "Action");

        Map<String, Double> profile = UserProfileBuilder.build(List.of(game), Map.of(1, details));

        assertThat(profile).containsKeys("RPG", "Action");
        assertThat(profile.get("RPG")).isEqualTo(10.0);
        assertThat(profile.get("Action")).isEqualTo(10.0);
    }

    private UserGameDTO ratedGame(int rawgId, int rating) {
        UserGameDTO game = new UserGameDTO();
        game.setRawgGameId(rawgId);
        game.setRating(rating);
        return game;
    }

    private GameDTO gameWithGenres(int rawgId, String... genres) {
        GameDTO game = new GameDTO();
        game.setRawgId(rawgId);
        game.setGenres(List.of(genres));
        return game;
    }
}