package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        UserProfile empty = new UserProfile(new HashMap<>(), new HashMap<>(), new HashMap<>(), 0);

        assertThat(SimilarityScorer.scoreMultiDim(candidate, empty)).isEqualTo(0.0);
    }

    @Test
    void scoreMultiDim_weights_tag_overlap_above_genre_overlap() {
        Map<String, Double> genres = Map.of("RPG", 9.0);
        Map<String, Double> themes = Map.of("Fantasy", 9.0);
        Map<String, Double> tags = Map.of("souls-like", 9.0);
        UserProfile profile = new UserProfile(genres, themes, tags, 1);

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
        UserProfile profile = new UserProfile(new HashMap<>(), new HashMap<>(), tags, 1);

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
                Map.of("RPG", 9.0), new HashMap<>(), new HashMap<>(), 1);

        GameDTO unrated = new GameDTO();
        unrated.setGenres(List.of("RPG"));
        unrated.setRating(null);

        // Should still score positive on the genre overlap, just no rating prior contribution.
        assertThat(SimilarityScorer.scoreMultiDim(unrated, profile)).isGreaterThan(0.0);
    }

    private GameDTO gameWithGenres(String... genres) {
        GameDTO game = new GameDTO();
        game.setGenres(List.of(genres));
        return game;
    }
}