package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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

    @Test
    void buildMultiDim_accumulates_rating_weighted_platform_counts() {
        // 9★ → weight 4, 10★ → weight 5. 6★ → weight 1. ≤5 → 0.
        UserGameDTO ps5Loved = ratedOnPlatform(1, 9, "PlayStation 5");
        UserGameDTO ps5GreatToo = ratedOnPlatform(2, 10, "PlayStation 5");
        UserGameDTO pcOk = ratedOnPlatform(3, 6, "PC");
        UserGameDTO ps5Mediocre = ratedOnPlatform(4, 5, "PlayStation 5"); // ≤5 → contributes 0

        UserProfile profile = UserProfileBuilder.buildMultiDim(
                List.of(ps5Loved, ps5GreatToo, pcOk, ps5Mediocre));

        // Raw counts pre-normalisation: PS5 = 4+5 = 9, PC = 1.
        // Sqrt: sqrt(9)=3, sqrt(1)=1. Sum=4. Normalised: PS5 = 0.75, PC = 0.25.
        assertThat(profile.platforms()).containsKey("PlayStation 5");
        assertThat(profile.platforms()).containsKey("PC");
        assertThat(profile.platforms().get("PlayStation 5")).isCloseTo(0.75, within(1e-9));
        assertThat(profile.platforms().get("PC")).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void buildMultiDim_platforms_sum_to_one_after_sqrt_normalisation() {
        // Arbitrary mix — only invariant is Σ w[p] = 1.0 after normalisation.
        UserGameDTO a = ratedOnPlatform(1, 9, "PlayStation 5");
        UserGameDTO b = ratedOnPlatform(2, 8, "PC");
        UserGameDTO c = ratedOnPlatform(3, 7, "Switch");

        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(a, b, c));

        double sum = profile.platforms().values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void buildMultiDim_single_platform_user_gets_weight_one() {
        UserGameDTO g1 = ratedOnPlatform(1, 9, "PC");
        UserGameDTO g2 = ratedOnPlatform(2, 8, "PC");
        UserGameDTO g3 = ratedOnPlatform(3, 7, "PC");

        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(g1, g2, g3));

        // sqrt(N)/sqrt(N) = 1.0 regardless of N, by construction.
        assertThat(profile.platforms()).hasSize(1);
        assertThat(profile.platforms().get("PC")).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void buildMultiDim_skips_platform_when_rating_at_or_below_5() {
        UserGameDTO mediocre = ratedOnPlatform(1, 5, "PlayStation 5");
        UserGameDTO bad = ratedOnPlatform(2, 3, "PC");

        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(mediocre, bad));

        // Both ratings below threshold contribute 0 weight → empty platforms map.
        assertThat(profile.platforms()).isEmpty();
    }

    @Test
    void buildMultiDim_skips_unrated_games_in_platform_accumulation() {
        UserGameDTO unrated = new UserGameDTO();
        unrated.setIgdbGameId(1);
        unrated.setRating(null);
        unrated.setPlatform("PlayStation 5");

        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(unrated));

        assertThat(profile.platforms()).isEmpty();
    }

    @Test
    void buildMultiDim_skips_null_or_blank_platform_values() {
        UserGameDTO nullPlat = new UserGameDTO();
        nullPlat.setIgdbGameId(1);
        nullPlat.setRating(9);
        nullPlat.setPlatform(null);

        UserGameDTO blankPlat = new UserGameDTO();
        blankPlat.setIgdbGameId(2);
        blankPlat.setRating(9);
        blankPlat.setPlatform("   ");

        UserGameDTO valid = ratedOnPlatform(3, 9, "PC");

        UserProfile profile = UserProfileBuilder.buildMultiDim(
                List.of(nullPlat, blankPlat, valid));

        assertThat(profile.platforms()).hasSize(1);
        assertThat(profile.platforms()).containsOnlyKeys("PC");
    }

    @Test
    void buildMultiDim_sqrt_softens_skewed_distribution() {
        // 90 PS5 / 10 PC user — expected sqrt-normalised weights: PS5 ≈ 0.75, PC ≈ 0.25.
        // Build via 90 PS5 games rated 6★ each (weight 1) + 10 PC games rated 6★ each.
        java.util.List<UserGameDTO> games = new java.util.ArrayList<>();
        for (int i = 0; i < 90; i++) games.add(ratedOnPlatform(i, 6, "PlayStation 5"));
        for (int i = 90; i < 100; i++) games.add(ratedOnPlatform(i, 6, "PC"));

        UserProfile profile = UserProfileBuilder.buildMultiDim(games);

        // Raw: PS5=90, PC=10. Sqrt: 9.487..., 3.162... Sum: 12.649. PS5=0.75, PC=0.25.
        assertThat(profile.platforms().get("PlayStation 5")).isCloseTo(0.75, within(0.005));
        assertThat(profile.platforms().get("PC")).isCloseTo(0.25, within(0.005));
    }

    @Test
    void buildMultiDim_empty_input_yields_empty_platform_map() {
        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of());

        assertThat(profile.platforms()).isEmpty();
        assertThat(profile.isEmpty()).isTrue();
    }

    @Test
    void buildMultiDim_primary_platform_gets_boosted_weight() {
        // Library matches the user's actual data: PS5 weighted=69, PC=23, PS4=6.
        java.util.List<UserGameDTO> games = new java.util.ArrayList<>();
        // 25 PS5 games to land on weighted total ~69 (mix of 6/7/8/9/10★).
        games.add(ratedOnPlatform(1, 9, "PlayStation 5"));  // 4
        games.add(ratedOnPlatform(2, 10, "PlayStation 5")); // 5
        games.add(ratedOnPlatform(3, 8, "PlayStation 5"));  // 3
        games.add(ratedOnPlatform(4, 9, "PlayStation 5"));  // 4
        // ... a tiny representative sample is enough for the multiplier test
        games.add(ratedOnPlatform(5, 7, "PC"));             // 2

        UserProfile noPrimary = UserProfileBuilder.buildMultiDim(games, List.of());
        UserProfile psPrimary = UserProfileBuilder.buildMultiDim(games,
                List.of(platformDto("PlayStation 5", true), platformDto("PC", false)));

        // PS5 weight should rise after primary boost; PC weight should drop (renormalisation).
        assertThat(psPrimary.platforms().get("PlayStation 5"))
                .isGreaterThan(noPrimary.platforms().get("PlayStation 5"));
        assertThat(psPrimary.platforms().get("PC"))
                .isLessThan(noPrimary.platforms().get("PC"));
    }

    @Test
    void buildMultiDim_no_primary_marked_behaves_identically_to_single_arg_overload() {
        // No is_primary set anywhere → buildMultiDim with platformList must equal single-arg.
        UserGameDTO ps5 = ratedOnPlatform(1, 9, "PlayStation 5");
        UserGameDTO pc = ratedOnPlatform(2, 8, "PC");

        UserProfile single = UserProfileBuilder.buildMultiDim(List.of(ps5, pc));
        UserProfile dual = UserProfileBuilder.buildMultiDim(
                List.of(ps5, pc),
                List.of(platformDto("PlayStation 5", false), platformDto("PC", false)));

        assertThat(dual.platforms()).isEqualTo(single.platforms());
    }

    @Test
    void buildMultiDim_primary_overload_with_empty_platforms_list_equals_single_arg() {
        UserGameDTO ps5 = ratedOnPlatform(1, 9, "PlayStation 5");
        UserGameDTO pc = ratedOnPlatform(2, 8, "PC");

        UserProfile single = UserProfileBuilder.buildMultiDim(List.of(ps5, pc));
        UserProfile dual = UserProfileBuilder.buildMultiDim(List.of(ps5, pc), List.of());

        assertThat(dual.platforms()).isEqualTo(single.platforms());
    }

    @Test
    void buildMultiDim_primary_on_platform_with_no_rated_games_stays_zero() {
        // User has rated only PC games but marked PS5 as primary. PS5 raw count = 0,
        // multiplier × 0 = 0 → PS5 stays absent from the profile (we don't conjure data).
        UserGameDTO pc1 = ratedOnPlatform(1, 9, "PC");
        UserGameDTO pc2 = ratedOnPlatform(2, 8, "PC");

        UserProfile profile = UserProfileBuilder.buildMultiDim(
                List.of(pc1, pc2),
                List.of(platformDto("PlayStation 5", true), platformDto("PC", false)));

        assertThat(profile.platforms()).doesNotContainKey("PlayStation 5");
        assertThat(profile.platforms()).containsKey("PC");
        assertThat(profile.platforms().get("PC")).isEqualTo(1.0);
    }

    @Test
    void buildMultiDim_all_platforms_marked_primary_cancels_to_no_op() {
        // If every platform is marked primary, the multiplier applies uniformly and
        // sqrt-normalisation produces the same distribution as without any primary.
        UserGameDTO ps5 = ratedOnPlatform(1, 9, "PlayStation 5");
        UserGameDTO pc = ratedOnPlatform(2, 8, "PC");

        UserProfile noPrimary = UserProfileBuilder.buildMultiDim(List.of(ps5, pc), List.of());
        UserProfile allPrimary = UserProfileBuilder.buildMultiDim(
                List.of(ps5, pc),
                List.of(platformDto("PlayStation 5", true), platformDto("PC", true)));

        assertThat(allPrimary.platforms().get("PlayStation 5"))
                .isCloseTo(noPrimary.platforms().get("PlayStation 5"), within(1e-9));
        assertThat(allPrimary.platforms().get("PC"))
                .isCloseTo(noPrimary.platforms().get("PC"), within(1e-9));
    }

    @Test
    void buildMultiDim_primary_multiplier_doubles_raw_count_before_normalisation() {
        // PS5 raw = 4 (one 9★ game), PC raw = 4 (one 9★). Without primary: equal weights 0.5/0.5.
        // With PS5 primary (×2): PS5 raw = 8, PC raw = 4. Sqrt: 2.828 / 2.0. Sum = 4.828.
        // PS5 = 0.586, PC = 0.414.
        UserGameDTO ps5 = ratedOnPlatform(1, 9, "PlayStation 5");
        UserGameDTO pc = ratedOnPlatform(2, 9, "PC");

        UserProfile profile = UserProfileBuilder.buildMultiDim(
                List.of(ps5, pc),
                List.of(platformDto("PlayStation 5", true), platformDto("PC", false)));

        assertThat(profile.platforms().get("PlayStation 5")).isCloseTo(0.586, within(0.005));
        assertThat(profile.platforms().get("PC")).isCloseTo(0.414, within(0.005));
    }

    @Test
    void buildMultiDim_null_platform_list_is_safe() {
        UserGameDTO ps5 = ratedOnPlatform(1, 9, "PlayStation 5");

        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(ps5), null);

        assertThat(profile.platforms().get("PlayStation 5")).isEqualTo(1.0);
    }

    private UserPlatformDTO platformDto(String name, boolean isPrimary) {
        UserPlatformDTO p = new UserPlatformDTO();
        p.setPlatformName(name);
        p.setIsPrimary(isPrimary);
        return p;
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

    private UserGameDTO ratedOnPlatform(int igdbId, int rating, String platform) {
        UserGameDTO game = new UserGameDTO();
        game.setIgdbGameId(igdbId);
        game.setRating(rating);
        game.setPlatform(platform);
        return game;
    }
}
