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

        // 9★ contributes 4, 6★ contributes 1 (4× ratio, sharper than the prior 1.5× when
        // raw rating drove the weight). open world should land at 5.
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
        // themes + tags left null (healing may not have completed for this row)
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
        // Arbitrary mix. Only invariant is Σ w[p] = 1.0 after normalisation.
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
        // 90 PS5 / 10 PC user. Expected sqrt-normalised weights: PS5 ≈ 0.75, PC ≈ 0.25.
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

    @Test
    void buildMultiDim_no_preferences_no_rated_games_returns_empty_profile() {
        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(), List.of(), List.of());

        assertThat(profile.genres()).isEmpty();
        assertThat(profile.themes()).isEmpty();
        assertThat(profile.tags()).isEmpty();
        assertThat(profile.platforms()).isEmpty();
        assertThat(profile.ratedGameCount()).isZero();
    }

    @Test
    void buildMultiDim_no_preferences_many_rated_games_uses_raw_rating_weights() {
        // No preferences → blend skipped → genres dimension is the raw rating-weighted accumulator
        // (backward-compatible with the two-arg overload). Each 9-star game contributes
        // weightFor(9) = 4; 8 RPG games + 2 Action games gives RPG=32, Action=8.
        List<UserGameDTO> games = List.of(
                ratedGame(1, 9, "RPG"),
                ratedGame(2, 9, "RPG"),
                ratedGame(3, 9, "RPG"),
                ratedGame(4, 9, "RPG"),
                ratedGame(5, 9, "RPG"),
                ratedGame(6, 9, "RPG"),
                ratedGame(7, 9, "RPG"),
                ratedGame(8, 9, "RPG"),
                ratedGame(9, 9, "Action"),
                ratedGame(10, 9, "Action")
        );

        UserProfile profile = UserProfileBuilder.buildMultiDim(games, List.of(), List.of());

        assertThat(profile.genres().get("RPG")).isCloseTo(32.0, within(0.001));
        assertThat(profile.genres().get("Action")).isCloseTo(8.0, within(0.001));
    }

    @Test
    void buildMultiDim_preferences_no_rated_games_blend_equals_uniform_prior() {
        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(), List.of(),
                List.of("RPG", "Strategy", "Roguelike"));

        // Three preferred genres, each 1/3 since alpha = 0 (no rated games).
        assertThat(profile.genres()).hasSize(3);
        assertThat(profile.genres().get("RPG")).isCloseTo(1.0 / 3.0, within(0.001));
        assertThat(profile.genres().get("Strategy")).isCloseTo(1.0 / 3.0, within(0.001));
        assertThat(profile.genres().get("Roguelike")).isCloseTo(1.0 / 3.0, within(0.001));
    }

    @Test
    void buildMultiDim_preferences_and_rated_at_cap_preferences_retain_floor() {
        // 10 rated games hits PREFERENCE_BLEND_CAP exactly: alpha caps at (1 - FLOOR) = 0.85,
        // so the prior retains FLOOR = 0.15 of mass instead of being discarded entirely.
        List<UserGameDTO> games = List.of(
                ratedGame(1, 9, "RPG"), ratedGame(2, 9, "RPG"), ratedGame(3, 9, "RPG"),
                ratedGame(4, 9, "RPG"), ratedGame(5, 9, "RPG"), ratedGame(6, 9, "RPG"),
                ratedGame(7, 9, "RPG"), ratedGame(8, 9, "RPG"), ratedGame(9, 9, "RPG"),
                ratedGame(10, 9, "RPG")
        );

        UserProfile profile = UserProfileBuilder.buildMultiDim(games, List.of(),
                List.of("Strategy", "Roguelike"));

        // RPG = 0.85 * 1.0 (full evidence share) + 0.15 * 0 (not in prior) = 0.85.
        // Strategy = 0.85 * 0 + 0.15 * 0.5 (half of two-prefs uniform) = 0.075.
        // Roguelike same as Strategy = 0.075.
        assertThat(profile.genres()).containsOnlyKeys("RPG", "Strategy", "Roguelike");
        assertThat(profile.genres().get("RPG")).isCloseTo(0.85, within(0.001));
        assertThat(profile.genres().get("Strategy")).isCloseTo(0.075, within(0.001));
        assertThat(profile.genres().get("Roguelike")).isCloseTo(0.075, within(0.001));
    }

    @Test
    void buildMultiDim_preferences_far_above_cap_still_retain_floor() {
        // 100 rated games, well past PREFERENCE_BLEND_CAP. Floor still active, so prior
        // contribution stays at exactly FLOOR = 0.15 regardless of how many ratings pile up.
        List<UserGameDTO> games = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            games.add(ratedGame(i, 9, "RPG"));
        }

        UserProfile profile = UserProfileBuilder.buildMultiDim(games, List.of(),
                List.of("Strategy"));

        assertThat(profile.genres().get("RPG")).isCloseTo(0.85, within(0.001));
        assertThat(profile.genres().get("Strategy")).isCloseTo(0.15, within(0.001));
    }

    @Test
    void buildMultiDim_tag_preferences_blended_with_rating_evidence() {
        // Mirror of the genre blend, exercised on the tag dimension via the four-arg overload.
        // 5 rated games puts alpha at 0.5; tag prior "cozy" should contribute alongside the
        // rated tag "atmospheric".
        List<UserGameDTO> games = List.of(
                ratedGameWithTag(1, 9, "Action", "atmospheric"),
                ratedGameWithTag(2, 9, "Action", "atmospheric"),
                ratedGameWithTag(3, 9, "Action", "atmospheric"),
                ratedGameWithTag(4, 9, "Action", "atmospheric"),
                ratedGameWithTag(5, 9, "Action", "atmospheric")
        );

        UserProfile profile = UserProfileBuilder.buildMultiDim(games, List.of(),
                List.of(), List.of("cozy"));

        // atmospheric = 0.5 * 1.0 + 0.5 * 0 = 0.5.
        // cozy = 0.5 * 0 + 0.5 * 1.0 = 0.5.
        assertThat(profile.tags()).containsOnlyKeys("atmospheric", "cozy");
        assertThat(profile.tags().get("atmospheric")).isCloseTo(0.5, within(0.001));
        assertThat(profile.tags().get("cozy")).isCloseTo(0.5, within(0.001));
    }

    @Test
    void buildMultiDim_tag_preferences_alone_with_no_ratings_form_uniform_prior() {
        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(), List.of(),
                List.of(), List.of("cozy", "atmospheric", "story rich"));

        assertThat(profile.tags()).hasSize(3);
        assertThat(profile.tags().get("cozy")).isCloseTo(1.0 / 3.0, within(0.001));
        assertThat(profile.tags().get("atmospheric")).isCloseTo(1.0 / 3.0, within(0.001));
        assertThat(profile.tags().get("story rich")).isCloseTo(1.0 / 3.0, within(0.001));
    }

    @Test
    void buildMultiDim_tag_preferences_at_cap_retain_floor() {
        // Mirror of the genre at-cap-floor test for the tag dimension. 10 ratings of a single
        // tag put alpha at the cap (0.85); declared tag "cozy" survives with weight 0.15.
        List<UserGameDTO> games = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            games.add(ratedGameWithTag(i, 9, "Action", "atmospheric"));
        }

        UserProfile profile = UserProfileBuilder.buildMultiDim(games, List.of(),
                List.of(), List.of("cozy"));

        assertThat(profile.tags().get("atmospheric")).isCloseTo(0.85, within(0.001));
        assertThat(profile.tags().get("cozy")).isCloseTo(0.15, within(0.001));
    }

    @Test
    void buildMultiDim_four_arg_with_empty_tag_prefs_equivalent_to_three_arg() {
        // The four-arg overload with an empty tagPreferences list must produce the same
        // tag dimension as the three-arg overload. Guards against accidental behavioural
        // divergence between the overloads.
        List<UserGameDTO> games = List.of(
                ratedGameWithTag(1, 9, "RPG", "story rich"),
                ratedGameWithTag(2, 8, "RPG", "atmospheric")
        );

        UserProfile threeArg = UserProfileBuilder.buildMultiDim(games, List.of(), List.of());
        UserProfile fourArg = UserProfileBuilder.buildMultiDim(games, List.of(), List.of(), List.of());

        assertThat(fourArg.tags()).isEqualTo(threeArg.tags());
        assertThat(fourArg.genres()).isEqualTo(threeArg.genres());
    }

    private UserGameDTO ratedGameWithTag(int igdbId, int rating, String genre, String tag) {
        UserGameDTO g = ratedGame(igdbId, rating, genre);
        g.setTags(List.of(tag));
        return g;
    }

    @Test
    void buildMultiDim_preferences_and_rated_halfway_both_inputs_visible() {
        // 5 rated games puts alpha at 0.5 exactly. Half ratings, half preferences.
        List<UserGameDTO> games = List.of(
                ratedGame(1, 9, "RPG"),
                ratedGame(2, 9, "RPG"),
                ratedGame(3, 9, "RPG"),
                ratedGame(4, 9, "RPG"),
                ratedGame(5, 9, "RPG")
        );

        UserProfile profile = UserProfileBuilder.buildMultiDim(games, List.of(),
                List.of("Strategy", "Roguelike"));

        // RPG = 0.5 * 1.0 (only rated genre, full share) + 0.5 * 0 (not in prior) = 0.5.
        // Strategy = 0.5 * 0 + 0.5 * 0.5 (half of two-prefs uniform) = 0.25.
        // Roguelike = same as Strategy = 0.25.
        assertThat(profile.genres()).containsOnlyKeys("RPG", "Strategy", "Roguelike");
        assertThat(profile.genres().get("RPG")).isCloseTo(0.5, within(0.001));
        assertThat(profile.genres().get("Strategy")).isCloseTo(0.25, within(0.001));
        assertThat(profile.genres().get("Roguelike")).isCloseTo(0.25, within(0.001));
    }

    @Test
    void buildMultiDim_preference_for_genre_with_no_ratings_appears_with_prior_weight() {
        // 2 rated RPG games, alpha = 2/10 = 0.2.
        UserProfile profile = UserProfileBuilder.buildMultiDim(
                List.of(ratedGame(1, 9, "RPG"), ratedGame(2, 9, "RPG")),
                List.of(),
                List.of("Strategy"));

        // Strategy contributed only by prior: (1 - 0.2) * 1.0 (only-pref share) = 0.8.
        assertThat(profile.genres().get("Strategy")).isCloseTo(0.8, within(0.001));
    }

    @Test
    void buildMultiDim_rated_genre_with_no_preference_appears_with_evidence_weight() {
        // 2 rated RPG, alpha = 0.2. Prior = ["Strategy"].
        UserProfile profile = UserProfileBuilder.buildMultiDim(
                List.of(ratedGame(1, 9, "RPG"), ratedGame(2, 9, "RPG")),
                List.of(),
                List.of("Strategy"));

        // RPG contributed only by ratings: 0.2 * 1.0 (only-rated share) = 0.2.
        assertThat(profile.genres().get("RPG")).isCloseTo(0.2, within(0.001));
    }

    @Test
    void buildMultiDim_blank_and_null_preference_names_are_dropped() {
        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(), List.of(),
                java.util.Arrays.asList("RPG", " ", "", null, "Strategy"));

        assertThat(profile.genres()).containsOnlyKeys("RPG", "Strategy");
        assertThat(profile.genres().get("RPG")).isCloseTo(0.5, within(0.001));
        assertThat(profile.genres().get("Strategy")).isCloseTo(0.5, within(0.001));
    }

    @Test
    void buildMultiDim_duplicate_preference_names_are_deduped_after_trim() {
        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(), List.of(),
                List.of("RPG", " RPG ", "Strategy"));

        // Deduped to {RPG, Strategy}, each 1/2.
        assertThat(profile.genres()).containsOnlyKeys("RPG", "Strategy");
        assertThat(profile.genres().get("RPG")).isCloseTo(0.5, within(0.001));
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

    private UserGameDTO ratedWithRelease(int igdbId, int rating, String released) {
        UserGameDTO game = new UserGameDTO();
        game.setIgdbGameId(igdbId);
        game.setRating(rating);
        game.setReleased(released);
        return game;
    }

    @Test
    void releaseYearBucket_maps_decades_correctly() {
        assertThat(UserProfileBuilder.releaseYearBucket("1985-07-15")).isEqualTo("Pre-1990");
        assertThat(UserProfileBuilder.releaseYearBucket("1989-12-31")).isEqualTo("Pre-1990");
        assertThat(UserProfileBuilder.releaseYearBucket("1990-01-01")).isEqualTo("1990s");
        assertThat(UserProfileBuilder.releaseYearBucket("1999-12-31")).isEqualTo("1990s");
        assertThat(UserProfileBuilder.releaseYearBucket("2005-06-12")).isEqualTo("2000s");
        assertThat(UserProfileBuilder.releaseYearBucket("2015-05-19")).isEqualTo("2010s");
        assertThat(UserProfileBuilder.releaseYearBucket("2020-01-01")).isEqualTo("2020s");
        assertThat(UserProfileBuilder.releaseYearBucket("2099-12-31")).isEqualTo("2020s");
    }

    @Test
    void releaseYearBucket_returns_null_for_null_blank_or_unparseable_input() {
        assertThat(UserProfileBuilder.releaseYearBucket(null)).isNull();
        assertThat(UserProfileBuilder.releaseYearBucket("")).isNull();
        assertThat(UserProfileBuilder.releaseYearBucket("abc")).isNull();
        assertThat(UserProfileBuilder.releaseYearBucket("not-a-date")).isNull();
    }

    @Test
    void buildMultiDim_accumulates_release_year_evidence_from_rated_games() {
        // Witcher 9★ (2015) → 2010s weight 4. Doom 8★ (2016) → 2010s weight 3. Elden 10★ (2022) → 2020s weight 5.
        UserGameDTO witcher = ratedWithRelease(1, 9, "2015-05-19");
        UserGameDTO doom = ratedWithRelease(2, 8, "2016-05-13");
        UserGameDTO elden = ratedWithRelease(3, 10, "2022-02-25");

        UserProfile profile = UserProfileBuilder.buildMultiDim(
                List.of(witcher, doom, elden), List.of(), List.of(), List.of(), List.of());

        assertThat(profile.releaseYears()).containsEntry("2010s", 7.0);
        assertThat(profile.releaseYears()).containsEntry("2020s", 5.0);
    }

    @Test
    void buildMultiDim_skips_null_release_dates_from_accumulator() {
        UserGameDTO dated = ratedWithRelease(1, 9, "2015-05-19");
        UserGameDTO undated = ratedWithRelease(2, 9, null);
        UserGameDTO empty = ratedWithRelease(3, 9, "");

        UserProfile profile = UserProfileBuilder.buildMultiDim(
                List.of(dated, undated, empty), List.of(), List.of(), List.of(), List.of());

        assertThat(profile.releaseYears()).containsOnlyKeys("2010s");
        assertThat(profile.releaseYears().get("2010s")).isEqualTo(4.0);
    }

    @Test
    void buildMultiDim_release_year_preferences_alone_form_uniform_prior() {
        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(), List.of(),
                List.of(), List.of(), List.of("1990s", "2010s", "2020s"));

        assertThat(profile.releaseYears()).hasSize(3);
        assertThat(profile.releaseYears().get("1990s")).isCloseTo(1.0 / 3.0, within(0.001));
        assertThat(profile.releaseYears().get("2010s")).isCloseTo(1.0 / 3.0, within(0.001));
        assertThat(profile.releaseYears().get("2020s")).isCloseTo(1.0 / 3.0, within(0.001));
    }

    @Test
    void buildMultiDim_release_year_preferences_blended_halfway_with_evidence() {
        // 5 rated games all in 2010s → alpha = 0.5. Pref = ["2020s"]. Evidence side fully 2010s,
        // prior side fully 2020s. Blend: 2010s = 0.5, 2020s = 0.5.
        List<UserGameDTO> games = List.of(
                ratedWithRelease(1, 9, "2015-01-01"),
                ratedWithRelease(2, 9, "2016-01-01"),
                ratedWithRelease(3, 9, "2017-01-01"),
                ratedWithRelease(4, 9, "2018-01-01"),
                ratedWithRelease(5, 9, "2019-01-01")
        );

        UserProfile profile = UserProfileBuilder.buildMultiDim(games, List.of(),
                List.of(), List.of(), List.of("2020s"));

        assertThat(profile.releaseYears()).containsOnlyKeys("2010s", "2020s");
        assertThat(profile.releaseYears().get("2010s")).isCloseTo(0.5, within(0.001));
        assertThat(profile.releaseYears().get("2020s")).isCloseTo(0.5, within(0.001));
    }

    @Test
    void buildMultiDim_release_year_preferences_at_cap_retain_floor() {
        // 10 rated games all in 2010s → alpha caps at 0.85; prior retains 0.15.
        List<UserGameDTO> games = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) games.add(ratedWithRelease(i, 9, "2015-01-01"));

        UserProfile profile = UserProfileBuilder.buildMultiDim(games, List.of(),
                List.of(), List.of(), List.of("2020s"));

        assertThat(profile.releaseYears().get("2010s")).isCloseTo(0.85, within(0.001));
        assertThat(profile.releaseYears().get("2020s")).isCloseTo(0.15, within(0.001));
    }

    @Test
    void buildMultiDim_five_arg_with_empty_release_year_prefs_equivalent_to_four_arg() {
        // Equivalence guard between the four-arg shim and the five-arg overload when prefs empty.
        List<UserGameDTO> games = List.of(
                ratedWithRelease(1, 9, "2015-05-19"),
                ratedWithRelease(2, 8, "2022-02-25")
        );

        UserProfile fourArg = UserProfileBuilder.buildMultiDim(games, List.of(), List.of(), List.of());
        UserProfile fiveArg = UserProfileBuilder.buildMultiDim(games, List.of(), List.of(), List.of(), List.of());

        assertThat(fiveArg.releaseYears()).isEqualTo(fourArg.releaseYears());
        assertThat(fiveArg.genres()).isEqualTo(fourArg.genres());
        assertThat(fiveArg.tags()).isEqualTo(fourArg.tags());
    }

    @Test
    void buildMultiDim_release_year_no_ratings_no_prefs_empty_map() {
        UserProfile profile = UserProfileBuilder.buildMultiDim(List.of(), List.of(),
                List.of(), List.of(), List.of());

        assertThat(profile.releaseYears()).isEmpty();
        assertThat(profile.declaredReleaseYears()).isEmpty();
        assertThat(profile.isEmpty()).isTrue();
    }

    @Test
    void buildMultiDim_declaredReleaseYears_captures_trimmed_deduped_picks() {
        // Declared set must contain exactly the user's picks (no rating-evidence leak).
        // Trims whitespace, drops blanks / nulls, dedupes.
        UserProfile profile = UserProfileBuilder.buildMultiDim(
                List.of(ratedWithRelease(1, 9, "2015-05-19")),
                List.of(), List.of(), List.of(),
                java.util.Arrays.asList(" Pre-1990 ", "Pre-1990", "", null, "2000s"));

        assertThat(profile.declaredReleaseYears()).containsExactlyInAnyOrder("Pre-1990", "2000s");
        // Rating evidence still accumulated in releaseYears even though the bucket is not declared.
        assertThat(profile.releaseYears()).containsKey("2010s");
    }
}
