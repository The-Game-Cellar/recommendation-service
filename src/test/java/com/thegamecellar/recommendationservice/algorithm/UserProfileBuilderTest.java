package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserProfileBuilderTest {

    @Test
    void build_returns_empty_profile_when_no_rated_games() {
        Map<String, Double> profile = UserProfileBuilder.build(List.of());

        assertThat(profile).isEmpty();
    }

    @Test
    void build_returns_correct_average_rating_for_genre() {
        UserGameDTO game1 = ratedGame(1, 8, "RPG");
        UserGameDTO game2 = ratedGame(2, 6, "RPG");

        Map<String, Double> profile = UserProfileBuilder.build(List.of(game1, game2));

        assertThat(profile).containsKey("RPG");
        assertThat(profile.get("RPG")).isEqualTo(7.0);
    }

    @Test
    void build_skips_games_with_null_genres() {
        UserGameDTO game = ratedGame(1, 9);

        Map<String, Double> profile = UserProfileBuilder.build(List.of(game));

        assertThat(profile).isEmpty();
    }

    @Test
    void build_handles_multiple_genres_per_game() {
        UserGameDTO game = ratedGame(1, 10, "RPG", "Action");

        Map<String, Double> profile = UserProfileBuilder.build(List.of(game));

        assertThat(profile).containsKeys("RPG", "Action");
        assertThat(profile.get("RPG")).isEqualTo(10.0);
        assertThat(profile.get("Action")).isEqualTo(10.0);
    }

    @Test
    void sampleWeighted_returns_empty_when_profile_is_empty() {
        assertThat(UserProfileBuilder.sampleWeighted(Map.of(), 5)).isEmpty();
    }

    @Test
    void sampleWeighted_returns_empty_when_k_is_zero_or_negative() {
        Map<String, Double> profile = Map.of("RPG", 5.0);
        assertThat(UserProfileBuilder.sampleWeighted(profile, 0)).isEmpty();
        assertThat(UserProfileBuilder.sampleWeighted(profile, -1)).isEmpty();
    }

    @Test
    void sampleWeighted_returns_at_most_k_distinct_keys_from_profile() {
        Map<String, Double> profile = Map.of(
                "RPG", 9.0,
                "Action", 7.0,
                "Strategy", 4.0,
                "Shooter", 6.0
        );
        for (int i = 0; i < 50; i++) {
            List<String> sample = UserProfileBuilder.sampleWeighted(profile, 3);
            assertThat(sample).hasSize(3);
            assertThat(sample).doesNotHaveDuplicates();
            assertThat(profile.keySet()).containsAll(sample);
        }
    }

    @Test
    void sampleWeighted_caps_at_profile_size_when_k_exceeds_profile() {
        Map<String, Double> profile = Map.of("RPG", 5.0, "Action", 4.0);
        List<String> sample = UserProfileBuilder.sampleWeighted(profile, 10);
        assertThat(sample).hasSize(2);
        assertThat(sample).containsExactlyInAnyOrder("RPG", "Action");
    }

    @Test
    void sampleWeighted_skips_zero_or_negative_weighted_entries() {
        Map<String, Double> profile = new java.util.HashMap<>();
        profile.put("RPG", 8.0);
        profile.put("Zero", 0.0);
        profile.put("Negative", -3.0);
        profile.put("Null", null);
        for (int i = 0; i < 30; i++) {
            List<String> sample = UserProfileBuilder.sampleWeighted(profile, 5);
            assertThat(sample).containsExactly("RPG");
        }
    }

    @Test
    void sampleWeighted_high_weight_dominates_over_many_trials() {
        Map<String, Double> profile = Map.of("Heavy", 100.0, "Light", 1.0);
        int heavyFirst = 0;
        int trials = 200;
        for (int i = 0; i < trials; i++) {
            List<String> sample = UserProfileBuilder.sampleWeighted(profile, 1);
            if (!sample.isEmpty() && sample.get(0).equals("Heavy")) heavyFirst++;
        }
        // With weight ratio 100:1, "Heavy" should be picked vastly more often.
        assertThat(heavyFirst).isGreaterThan((int) (trials * 0.85));
    }

    @Test
    void buildMultiDim_returns_empty_profile_when_no_rated_games() {
        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of());

        assertThat(profile.isEmpty()).isTrue();
        assertThat(profile.ratedGameCount()).isZero();
    }

    @Test
    void buildMultiDim_accumulates_rating_weight_across_dimensions() {
        // Witcher 9★ → weight 4. DarkSouls 10★ → weight 5.
        UserGameDTO witcher = new UserGameDTO();
        witcher.setIgdbGameId(1);
        witcher.setRating(9);
        witcher.setGenres(List.of("RPG", "Adventure"));
        witcher.setThemes(List.of("Fantasy"));
        witcher.setTags(List.of("open world", "story rich"));

        UserGameDTO darkSouls = new UserGameDTO();
        darkSouls.setIgdbGameId(2);
        darkSouls.setRating(10);
        darkSouls.setGenres(List.of("RPG"));
        darkSouls.setThemes(List.of("Fantasy", "Action"));
        darkSouls.setTags(List.of("souls-like", "punishing"));

        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(witcher, darkSouls));

        assertThat(profile.ratedGameCount()).isEqualTo(2);
        assertThat(profile.genres()).containsEntry("RPG", 9.0);          // 4 + 5
        assertThat(profile.genres()).containsEntry("Adventure", 4.0);    // witcher only
        assertThat(profile.themes()).containsEntry("Fantasy", 9.0);      // 4 + 5
        assertThat(profile.themes()).containsEntry("Action", 5.0);       // dark souls only
        assertThat(profile.tags()).containsEntry("souls-like", 5.0);
        assertThat(profile.tags()).containsEntry("open world", 4.0);
    }

    @Test
    void buildMultiDim_skips_ratings_at_or_below_5() {
        UserGameDTO mediocre = new UserGameDTO();
        mediocre.setIgdbGameId(1);
        mediocre.setRating(5);
        mediocre.setGenres(List.of("RPG"));
        mediocre.setTags(List.of("open world"));

        UserGameDTO bad = new UserGameDTO();
        bad.setIgdbGameId(2);
        bad.setRating(3);
        bad.setGenres(List.of("Action"));

        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(mediocre, bad));

        // Both rated below the threshold → nothing contributes to profile vectors.
        assertThat(profile.ratedGameCount()).isEqualTo(2);
        assertThat(profile.isEmpty()).isTrue();
    }

    @Test
    void buildMultiDim_high_rating_dominates_over_low_rating_for_same_feature() {
        UserGameDTO loved = new UserGameDTO();
        loved.setIgdbGameId(1);
        loved.setRating(9);
        loved.setTags(List.of("open world"));

        UserGameDTO ok = new UserGameDTO();
        ok.setIgdbGameId(2);
        ok.setRating(6);
        ok.setTags(List.of("open world"));

        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(loved, ok));

        // 9★ contributes 4, 6★ contributes 1 — 4× ratio, sharper than the prior 1.5× when
        // raw rating drove the weight. open world should land at 5.
        assertThat(profile.tags()).containsEntry("open world", 5.0);
    }

    @Test
    void buildMultiDim_skips_unrated_games() {
        UserGameDTO unrated = new UserGameDTO();
        unrated.setIgdbGameId(1);
        unrated.setRating(null);
        unrated.setGenres(List.of("RPG"));
        unrated.setTags(List.of("open world"));

        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(unrated));

        assertThat(profile.isEmpty()).isTrue();
    }

    @Test
    void buildMultiDim_handles_null_dimension_lists_gracefully() {
        UserGameDTO sparse = new UserGameDTO();
        sparse.setIgdbGameId(1);
        sparse.setRating(8);
        sparse.setGenres(List.of("RPG"));
        // themes + tags left null — healing may not have completed for this row
        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(sparse));

        // Rated 8★ → weight = 8 - 5 = 3.
        assertThat(profile.genres()).containsEntry("RPG", 3.0);
        assertThat(profile.themes()).isEmpty();
        assertThat(profile.tags()).isEmpty();
    }

    private UserGameDTO ratedGame(int igdbId, int rating, String... genres) {
        UserGameDTO game = new UserGameDTO();
        game.setIgdbGameId(igdbId);
        game.setRating(rating);
        if (genres.length > 0) {
            game.setGenres(List.of(genres));
        }
        return game;
    }
}
