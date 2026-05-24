package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SimilarityScorerTest {

    @Test
    void score_returns_zero_when_candidate_has_no_genres() {
        GameDTO candidate = new GameDTO();
        candidate.setGenres(null);

        double score = SimilarityScorer.score(candidate, Map.of("RPG", 9.0));

        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void score_returns_zero_when_no_genres_match_profile() {
        GameDTO candidate = gameWithGenres("Strategy");

        double score = SimilarityScorer.score(candidate, Map.of("RPG", 8.0));

        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void score_returns_genre_match_score_on_match() {
        GameDTO candidate = gameWithGenres("RPG");

        double score = SimilarityScorer.score(candidate, Map.of("RPG", 5.0));

        assertThat(score).isEqualTo(2.0);
    }

    @Test
    void score_adds_high_rating_bonus_when_genre_rated_above_threshold() {
        GameDTO candidate = gameWithGenres("RPG");

        double score = SimilarityScorer.score(candidate, Map.of("RPG", 9.0));

        assertThat(score).isEqualTo(3.0); // 2.0 match + 1.0 bonus
    }

    @Test
    void score_increases_with_more_matching_genres() {
        GameDTO oneMatch = gameWithGenres("RPG", "Strategy");
        GameDTO twoMatches = gameWithGenres("RPG", "Action");
        Map<String, Double> profile = Map.of("RPG", 5.0, "Action", 5.0);

        assertThat(SimilarityScorer.score(twoMatches, profile))
                .isGreaterThan(SimilarityScorer.score(oneMatch, profile));
    }

    @Test
    void scoreByGenreOverlap_returns_count_of_matching_genres() {
        GameDTO candidate = gameWithGenres("RPG", "Action", "Strategy");

        double overlap = SimilarityScorer.scoreByGenreOverlap(candidate, List.of("RPG", "Action"));

        assertThat(overlap).isEqualTo(2.0);
    }

    @Test
    void scoreByGenreOverlap_returns_zero_when_no_overlap() {
        GameDTO candidate = gameWithGenres("Strategy");

        double overlap = SimilarityScorer.scoreByGenreOverlap(candidate, List.of("RPG"));

        assertThat(overlap).isEqualTo(0.0);
    }

    @Test
    void scoreMultiDim_returns_zero_for_empty_profile() {
        GameDTO candidate = new GameDTO();
        candidate.setGenres(List.of("RPG"));
        candidate.setTags(List.of("souls-like"));

        UserProfile empty = new UserProfile(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), Set.of(), 0);

        assertThat(SimilarityScorer.scoreMultiDim(candidate, empty)).isEqualTo(0.0);
    }

    @Test
    void scoreMultiDim_weights_tag_overlap_above_genre_overlap() {
        Map<String, Double> genres = Map.of("RPG", 9.0);
        Map<String, Double> themes = Map.of("Fantasy", 9.0);
        Map<String, Double> tags = Map.of("souls-like", 9.0);
        UserProfile profile = new UserProfile(genres, themes, tags, new HashMap<>(), new HashMap<>(), Set.of(), 1);

        // Pure-genre match (no theme/tag overlap with profile)
        GameDTO genreOnly = new GameDTO();
        genreOnly.setGenres(List.of("RPG"));
        genreOnly.setThemes(List.of("Sci-Fi"));
        genreOnly.setTags(List.of("space combat"));

        // Pure-tag match (no genre/theme overlap)
        GameDTO tagOnly = new GameDTO();
        tagOnly.setGenres(List.of("Action"));
        tagOnly.setThemes(List.of("Sci-Fi"));
        tagOnly.setTags(List.of("souls-like"));

        double genreScore = SimilarityScorer.scoreMultiDim(genreOnly, profile);
        double tagScore = SimilarityScorer.scoreMultiDim(tagOnly, profile);

        // γ=0.55 should dominate α=0.15
        assertThat(tagScore).isGreaterThan(genreScore);
    }

    @Test
    void scoreMultiDim_includes_rating_prior_for_highly_rated_candidates() {
        Map<String, Double> tags = Map.of("souls-like", 9.0);
        UserProfile profile = new UserProfile(new HashMap<>(), new HashMap<>(), tags, new HashMap<>(), new HashMap<>(), Set.of(), 1);

        GameDTO base = new GameDTO();
        base.setTags(List.of("souls-like"));
        base.setRating(BigDecimal.valueOf(6.0)); // prior = 0
        base.setTotalRatingCount(100);

        GameDTO highlyRated = new GameDTO();
        highlyRated.setTags(List.of("souls-like"));
        highlyRated.setRating(BigDecimal.valueOf(10.0)); // prior = 1
        highlyRated.setTotalRatingCount(100);

        assertThat(SimilarityScorer.scoreMultiDim(highlyRated, profile))
                .isGreaterThan(SimilarityScorer.scoreMultiDim(base, profile));
    }

    @Test
    void scoreMultiDim_clamps_rating_prior_for_invalid_ratings() {
        UserProfile profile = new UserProfile(
                Map.of("RPG", 9.0), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), Set.of(), 1);

        GameDTO unrated = new GameDTO();
        unrated.setGenres(List.of("RPG"));
        unrated.setRating(null);

        // Should still score positive on the genre overlap, just no rating prior contribution.
        assertThat(SimilarityScorer.scoreMultiDim(unrated, profile)).isGreaterThan(0.0);
    }

    @Test
    void platformBoost_returns_average_weight_across_intersection() {
        // User profile: PS5=0.75, PC=0.25.
        Map<String, Double> profile = Map.of("PlayStation 5", 0.75, "PC", 0.25);

        // Candidate available on PS5 + PC + Switch. Switch isn't in profile so excluded from
        // average; intersection = {PS5: 0.75, PC: 0.25}; avg = 0.50.
        GameDTO crossPlatform = new GameDTO();
        crossPlatform.setPlatforms(List.of("PlayStation 5", "PC", "Switch"));

        assertThat(SimilarityScorer.platformBoost(crossPlatform, profile)).isEqualTo(0.50);
    }

    @Test
    void platformBoost_pure_primary_outscores_cross_platform_for_skewed_user() {
        Map<String, Double> profile = Map.of("PlayStation 5", 0.75, "PC", 0.25);

        GameDTO purePrimary = new GameDTO();
        purePrimary.setPlatforms(List.of("PlayStation 5"));

        GameDTO crossPlatform = new GameDTO();
        crossPlatform.setPlatforms(List.of("PlayStation 5", "PC"));

        // Pure-primary = 0.75; cross-platform = 0.50 (avg). 0.25 gap reflects user skew.
        assertThat(SimilarityScorer.platformBoost(purePrimary, profile))
                .isGreaterThan(SimilarityScorer.platformBoost(crossPlatform, profile));
    }

    @Test
    void platformBoost_balanced_user_has_minimal_gap_between_pure_and_cross_platform() {
        // A 50/50 user, no real primary. Average self-scales: gap shrinks to zero.
        Map<String, Double> profile = Map.of("PlayStation 5", 0.50, "PC", 0.50);

        GameDTO purePrimary = new GameDTO();
        purePrimary.setPlatforms(List.of("PlayStation 5"));

        GameDTO crossPlatform = new GameDTO();
        crossPlatform.setPlatforms(List.of("PlayStation 5", "PC"));

        assertThat(SimilarityScorer.platformBoost(purePrimary, profile)).isEqualTo(0.50);
        assertThat(SimilarityScorer.platformBoost(crossPlatform, profile)).isEqualTo(0.50);
    }

    @Test
    void platformBoost_single_platform_user_yields_constant_boost_for_filter_passing_candidates() {
        // Single-platform user → singleton-average = same value for every candidate that
        // passes matchesAnyPlatform. Constant additive doesn't change relative ranking.
        Map<String, Double> profile = Map.of("PC", 1.0);

        GameDTO pcExclusive = new GameDTO();
        pcExclusive.setPlatforms(List.of("PC"));

        GameDTO pcAndOthers = new GameDTO();
        pcAndOthers.setPlatforms(List.of("PC", "Switch", "Xbox"));

        assertThat(SimilarityScorer.platformBoost(pcExclusive, profile)).isEqualTo(1.0);
        assertThat(SimilarityScorer.platformBoost(pcAndOthers, profile)).isEqualTo(1.0);
    }

    @Test
    void platformBoost_returns_zero_when_no_intersection() {
        Map<String, Double> profile = Map.of("PlayStation 5", 1.0);
        GameDTO pcOnly = new GameDTO();
        pcOnly.setPlatforms(List.of("PC"));

        assertThat(SimilarityScorer.platformBoost(pcOnly, profile)).isEqualTo(0.0);
    }

    @Test
    void platformBoost_returns_zero_for_null_candidate_platforms() {
        Map<String, Double> profile = Map.of("PC", 1.0);
        GameDTO sparse = new GameDTO();
        sparse.setPlatforms(null);

        assertThat(SimilarityScorer.platformBoost(sparse, profile)).isEqualTo(0.0);
    }

    @Test
    void platformBoost_returns_zero_for_empty_profile_platforms() {
        GameDTO candidate = new GameDTO();
        candidate.setPlatforms(List.of("PC"));

        assertThat(SimilarityScorer.platformBoost(candidate, new HashMap<>())).isEqualTo(0.0);
        assertThat(SimilarityScorer.platformBoost(candidate, null)).isEqualTo(0.0);
    }

    @Test
    void platformBoost_returns_secondary_weight_when_candidate_hits_secondary_only() {
        // User has PS5 primary (0.75) but the candidate is PC-only (0.25). Singleton avg = 0.25.
        Map<String, Double> profile = Map.of("PlayStation 5", 0.75, "PC", 0.25);
        GameDTO pcExclusive = new GameDTO();
        pcExclusive.setPlatforms(List.of("PC"));

        assertThat(SimilarityScorer.platformBoost(pcExclusive, profile)).isEqualTo(0.25);
    }

    @Test
    void platformBoost_handles_null_entries_in_candidate_platforms() {
        Map<String, Double> profile = Map.of("PC", 1.0);
        GameDTO sloppy = new GameDTO();
        sloppy.setPlatforms(java.util.Arrays.asList(null, "PC", null));

        assertThat(SimilarityScorer.platformBoost(sloppy, profile)).isEqualTo(1.0);
    }

    @Test
    void platformBoost_returns_zero_for_null_candidate() {
        Map<String, Double> profile = Map.of("PC", 1.0);
        assertThat(SimilarityScorer.platformBoost(null, profile)).isEqualTo(0.0);
    }

    private GameDTO gameWithGenres(String... genres) {
        GameDTO game = new GameDTO();
        game.setGenres(List.of(genres));
        return game;
    }

    @Test
    void releaseYearBoost_returns_one_on_declared_bucket_match() {
        Set<String> declared = Set.of("2010s", "2020s");
        GameDTO candidate = new GameDTO();
        candidate.setReleased("2015-05-19");

        assertThat(SimilarityScorer.releaseYearBoost(candidate, declared)).isEqualTo(1.0);
    }

    @Test
    void releaseYearBoost_returns_zero_on_miss_or_null_input() {
        Set<String> declared = Set.of("2020s");

        // Different bucket
        GameDTO outOfBucket = new GameDTO();
        outOfBucket.setReleased("1995-08-01");
        assertThat(SimilarityScorer.releaseYearBoost(outOfBucket, declared)).isEqualTo(0.0);

        // Null candidate
        assertThat(SimilarityScorer.releaseYearBoost(null, declared)).isEqualTo(0.0);

        // Null released
        GameDTO undated = new GameDTO();
        undated.setReleased(null);
        assertThat(SimilarityScorer.releaseYearBoost(undated, declared)).isEqualTo(0.0);

        // Empty declared set
        GameDTO dated = new GameDTO();
        dated.setReleased("2024-01-01");
        assertThat(SimilarityScorer.releaseYearBoost(dated, Set.of())).isEqualTo(0.0);
    }
}